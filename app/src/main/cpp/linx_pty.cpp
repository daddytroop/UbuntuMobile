#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "LinxPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * A minimal PTY implementation for Android.
 *
 * The NDK's bionic does not ship forkpty()/openpty(), so we implement the
 * classic POSIX sequence by hand:
 *
 *   open("/dev/ptmx") -> grantpt() -> unlockpt() -> ptsname()
 *
 * The child then becomes a session leader with the slave pty as its
 * controlling terminal, which is what makes interactive programs
 * (readline, vim, python REPL, top, ...) behave correctly.
 */

static int pty_slave_name(int master, char *buf, size_t len) {
    if (ptsname_r(master, buf, len) != 0) {
        char *p = ptsname(master);
        if (p == NULL) return -1;
        strncpy(buf, p, len - 1);
        buf[len - 1] = '\0';
    }
    return 0;
}

/*
 * Spawn a child process attached to a fresh PTY.
 *
 * Returns the master fd (ownership passes to the caller, which must close it),
 * or -1 on failure. The child's pid is written into pidOut[0].
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_ubuntumobile_linux_Pty_nativeSpawn(JNIEnv *env, jobject thiz,
                                             jobjectArray argv,
                                             jobjectArray envp,
                                             jstring workdir,
                                             jintArray winsize,
                                             jintArray pidOut) {
    (void) thiz;

    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) {
        LOGE("open(/dev/ptmx) failed: %s", strerror(errno));
        return -1;
    }
    if (grantpt(master) < 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    if (unlockpt(master) < 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    char slave_name[128];
    if (pty_slave_name(master, slave_name, sizeof(slave_name)) < 0) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(master);
        return -1;
    }

    /* Window size (rows, cols) so curses apps size themselves correctly. */
    if (winsize != nullptr) {
        jsize n = env->GetArrayLength(winsize);
        if (n >= 2) {
            jint rows = 0, cols = 0;
            env->GetIntArrayRegion(winsize, 0, 1, &rows);
            env->GetIntArrayRegion(winsize, 1, 1, &cols);
            struct winsize ws;
            memset(&ws, 0, sizeof(ws));
            ws.ws_row = (unsigned short) rows;
            ws.ws_col = (unsigned short) cols;
            ioctl(master, TIOCSWINSZ, &ws);
        }
    }

    int slave = open(slave_name, O_RDWR);
    if (slave < 0) {
        LOGE("open(slave %s) failed: %s", slave_name, strerror(errno));
        close(master);
        return -1;
    }

    jsize argc = env->GetArrayLength(argv);
    jsize envc = env->GetArrayLength(envp);

    char **cargv = (char **) calloc(argc + 1, sizeof(char *));
    char **cenvp = (char **) calloc(envc + 1, sizeof(char *));
    if (!cargv || !cenvp) {
        free(cargv); free(cenvp);
        close(master); close(slave);
        return -1;
    }

    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(argv, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        cargv[i] = strdup(c);
        env->ReleaseStringUTFChars(s, c);
    }
    for (jsize i = 0; i < envc; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(envp, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        cenvp[i] = strdup(c);
        env->ReleaseStringUTFChars(s, c);
    }

    const char *cwd = nullptr;
    if (workdir != nullptr) {
        cwd = env->GetStringUTFChars(workdir, nullptr);
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        free(cargv); free(cenvp);
        if (cwd) env->ReleaseStringUTFChars(workdir, cwd);
        close(master); close(slave);
        return -1;
    }

    if (pid == 0) {
        /* ---------------- child ---------------- */
        close(master);

        /* New session so the slave can become the controlling terminal. */
        if (setsid() < 0) _exit(127);
        if (ioctl(slave, TIOCSCTTY, 0) < 0) _exit(127);

        /* Wire the slave pty to stdin/stdout/stderr. */
        if (dup2(slave, STDIN_FILENO) < 0) _exit(127);
        if (dup2(slave, STDOUT_FILENO) < 0) _exit(127);
        if (dup2(slave, STDERR_FILENO) < 0) _exit(127);
        if (slave > STDERR_FILENO) close(slave);

        /* Restore default signal dispositions and unblock everything. */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, nullptr);

        if (cwd != nullptr) {
            if (chdir(cwd) != 0) { /* non-fatal */ }
        }

        execve(cargv[0], cargv, cenvp);
        LOGE("execve(%s) failed: %s", cargv[0], strerror(errno));
        _exit(127);
    }

    /* ---------------- parent ---------------- */
    close(slave);
    if (cwd) env->ReleaseStringUTFChars(workdir, cwd);
    for (jsize i = 0; i < argc; i++) free(cargv[i]);
    for (jsize i = 0; i < envc; i++) free(cenvp[i]);
    free(cargv); free(cenvp);

    if (pidOut != nullptr && env->GetArrayLength(pidOut) > 0) {
        jint p = (jint) pid;
        env->SetIntArrayRegion(pidOut, 0, 1, &p);
    }

    return (jint) master;
}

/* Wait for the child and return its exit status. */
extern "C" JNIEXPORT jint JNICALL
Java_com_ubuntumobile_linux_Pty_nativeWaitPid(JNIEnv *env, jobject thiz, jint pid) {
    (void) env; (void) thiz;
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

/* Non-blocking check: 0 = still running, >0 = exit status, -1 = error. */
extern "C" JNIEXPORT jint JNICALL
Java_com_ubuntumobile_linux_Pty_nativeTryWait(JNIEnv *env, jobject thiz, jint pid) {
    (void) env; (void) thiz;
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r == 0) return 0;
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

/* Change the pty window size (rows, cols). */
extern "C" JNIEXPORT void JNICALL
Java_com_ubuntumobile_linux_Pty_nativeSetWinsize(JNIEnv *env, jobject thiz,
                                                  jint fd, jint rows, jint cols) {
    (void) env; (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl((int) fd, TIOCSWINSZ, &ws);
}

/* Signal the foreground process group of the pty (Ctrl-C, Ctrl-Z, ...). */
extern "C" JNIEXPORT void JNICALL
Java_com_ubuntumobile_linux_Pty_nativeSignal(JNIEnv *env, jobject thiz,
                                             jint fd, jint sig) {
    (void) env; (void) thiz;
    pid_t pgrp = tcgetpgrp((int) fd);
    if (pgrp > 0) {
        kill(-pgrp, sig);
    }
}
