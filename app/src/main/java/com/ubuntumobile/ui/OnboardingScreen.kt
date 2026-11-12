package com.ubuntumobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntumobile.ui.theme.UbuntuPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How much Linux to set up. */
enum class SetupProfile(val label: String, val detail: String) {
    LIGHT("Light", "The desktop, terminal and files. Add apps whenever you like."),
    STANDARD("Standard", "Adds Firefox and a document viewer. A good start."),
    FULL("Full", "Adds an office suite, photo editing, a video player and developer tools."),
}

/** Yaru's accent colours (the theme names are Yaru-<id>). */
enum class Accent(val id: String, val label: String, val color: Long) {
    ORANGE("orange", "Orange", 0xFFE95420),
    RED("red", "Red", 0xFFDA3450),
    MAGENTA("magenta", "Magenta", 0xFFB34CB3),
    PURPLE("purple", "Purple", 0xFF7764D8),
    BLUE("blue", "Blue", 0xFF0073E5),
    TEAL("prussiangreen", "Teal", 0xFF308280),
    SAGE("sage", "Sage", 0xFF657B69),
    OLIVE("olive", "Olive", 0xFF4B8501),
}

/** A developer tool that onboarding offers: an Ubuntu package of a GitHub project. */
data class DevPick(val pkg: String, val name: String, val summary: String, val repo: String, val mb: Int)

/** Everything onboarding asks. */
data class SetupChoices(
    val profile: SetupProfile = SetupProfile.STANDARD,
    val dark: Boolean = false,
    val accent: Accent = Accent.ORANGE,
    val desktopSize: DesktopSize = DesktopSize.STANDARD,
    val browser: Boolean = true,
    /** Packages of [StoreCatalog.featured] apps (not Firefox). */
    val apps: Set<String> = defaultApps(SetupProfile.STANDARD),
    val flathub: Boolean = false,
    val flatpaks: Set<String> = emptySet(),
    val wallpaper: Wallpaper = Wallpaper.AUBERGINE,
    /** Packages of [devPicks]. */
    val devTools: Set<String> = emptySet(),
    /** What it is for ("What will you use it for?"). */
    val interests: Set<Interest> = emptySet(),
) {
    /** Pick or drop an interest, ticking or unticking the apps suggested for it. */
    fun withInterest(interest: Interest, on: Boolean): SetupChoices {
        val now = if (on) interests + interest else interests - interest
        val added = Collections.picks(setOf(interest))
        // Apps another chosen interest also suggests stay.
        val kept = Collections.picks(now).map { it.id }.toSet()
        val (ubuntu, flathubApps) = added.partition { it.ubuntu != null }
        return if (on) {
            copy(
                interests = now,
                apps = apps + ubuntu.map { it.id },
                flatpaks = flatpaks + flathubApps.map { it.id },
                flathub = flathub || flathubApps.isNotEmpty(),
            )
        } else {
            copy(
                interests = now,
                apps = apps - ubuntu.map { it.id }.filter { it !in kept }.toSet(),
                flatpaks = flatpaks - flathubApps.map { it.id }.filter { it !in kept }.toSet(),
            )
        }
    }

    /** The packages to install with apt. */
    val aptPackages: List<String>
        get() = (
            StoreCatalog.featured.filter { it.pkg in apps && it.installer == null }.flatMap { it.packages } +
                devPicks.filter { it.pkg in devTools }.map { it.pkg }
            ).distinct()

    /** About how much there is to download, in MB. */
    val downloadMb: Int
        get() {
            var mb = 70 // sound, Yaru, the Ubuntu font and the everyday apps
            if (browser) mb += 90
            if (apps.any { it.startsWith("libreoffice") }) mb += 190
            mb += apps.sumOf { APP_SIZES[it] ?: 20 }
            mb += devPicks.filter { it.pkg in devTools }.sumOf { it.mb }
            if (flathub) mb += 5
            if (flatpaks.isNotEmpty()) mb += 350 + flatpaks.size * 60
            return mb
        }

    /** /etc/linx/setup.conf, as linx-setup.sh reads it. */
    fun toConf(): String = buildString {
        append("# Written by the app's onboarding; read by linx-setup.sh and linx-look.sh.\n")
        append("LINX_PROFILE=${profile.name.lowercase()}\n")
        append("LINX_BROWSER=${if (browser) 1 else 0}\n")
        append("LINX_APPS=\"${aptPackages.joinToString(" ")}\"\n")
        append("LINX_FLATHUB=${if (flathub) 1 else 0}\n")
        append("LINX_FLATPAKS=\"${if (flathub) flatpaks.joinToString(" ") else ""}\"\n")
        append("LINX_DARK=${if (dark) 1 else 0}\n")
        append("LINX_ACCENT=${accent.id}\n")
        append("LINX_WALLPAPER=${wallpaper.id}\n")
        append("LINX_INTERESTS=\"${interests.joinToString(" ") { it.id }}\"\n")
    }

    companion object {
        private val APP_SIZES = mapOf(
            "libreoffice-writer" to 15, "libreoffice-calc" to 15, "libreoffice-impress" to 15,
            "gimp" to 110, "inkscape" to 90, "vlc" to 50, "geany" to 10, "evince" to 15, "keepassxc" to 15,
        )

        fun defaultApps(profile: SetupProfile): Set<String> = when (profile) {
            SetupProfile.LIGHT -> emptySet()
            SetupProfile.STANDARD -> setOf("evince")
            SetupProfile.FULL -> setOf(
                "libreoffice-writer", "libreoffice-calc", "libreoffice-impress", "gimp", "vlc", "evince",
            )
        }

        /**
         * Popular open-source tools from GitHub. All of them are in
         * Ubuntu's archive, so they install with apt, signed and small.
         */
        val devPicks = listOf(
            DevPick("git", "Git", "Version control", "git/git", 12),
            DevPick("gh", "GitHub CLI", "Pull requests and issues from the terminal", "cli/cli", 15),
            DevPick("neovim", "Neovim", "The hyperextensible text editor", "neovim/neovim", 10),
            DevPick("lazygit", "lazygit", "Git in a friendly terminal UI", "jesseduffield/lazygit", 8),
            DevPick("btop", "btop", "A beautiful resource monitor", "aristocratic/btop", 2),
            DevPick("fastfetch", "fastfetch", "Your system at a glance", "fastfetch-cli/fastfetch", 2),
            DevPick("ripgrep", "ripgrep", "Search code, fast", "BurntSushi/ripgrep", 2),
            DevPick("fzf", "fzf", "A fuzzy finder for everything", "junegunn/fzf", 2),
            DevPick("bat", "bat", "cat with wings (run it as batcat)", "sharkdp/bat", 3),
            DevPick("zoxide", "zoxide", "A smarter cd", "ajeetdsouza/zoxide", 1),
            DevPick("tmux", "tmux", "Terminals that keep running", "tmux/tmux", 1),
        )

        fun defaultDevTools(profile: SetupProfile): Set<String> = when (profile) {
            SetupProfile.FULL -> setOf("git", "gh", "btop", "fastfetch")
            else -> emptySet()
        }

        /** Read back what [toConf] wrote (for "Set up again"). */
        fun fromConf(text: String, desktopSize: DesktopSize): SetupChoices {
            val values = text.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (line.startsWith("#") || i <= 0) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
            }.toMap()
            val packages = values["LINX_APPS"].orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
            return SetupChoices(
                profile = SetupProfile.entries.firstOrNull { it.name.lowercase() == values["LINX_PROFILE"] }
                    ?: SetupProfile.STANDARD,
                dark = values["LINX_DARK"] == "1",
                accent = Accent.entries.firstOrNull { it.id == values["LINX_ACCENT"] } ?: Accent.ORANGE,
                desktopSize = desktopSize,
                browser = values["LINX_BROWSER"] != "0",
                apps = StoreCatalog.featured.filter { it.installer == null && it.pkg in packages }.map { it.pkg }.toSet(),
                flathub = values["LINX_FLATHUB"] == "1",
                flatpaks = values["LINX_FLATPAKS"].orEmpty().split(' ').filter { it.isNotBlank() }.toSet(),
                wallpaper = Wallpaper.byId(values["LINX_WALLPAPER"]),
                devTools = devPicks.map { it.pkg }.filter { it in packages }.toSet(),
                interests = Interest.parse(values["LINX_INTERESTS"]),
            )
        }
    }
}

private enum class Page { WELCOME, PROFILE, USES, LOOK, APPS, DEV, STORES, PHONE, READY }

/**
 * The first thing a new user sees: a few questions, Ubuntu-installer
 * style, that decide how big Linux gets and how it looks. Ubuntu itself
 * unpacks in the background meanwhile ([unpackFraction]); what was chosen
 * is installed once the desktop is up (linx-setup.sh).
 */
@Composable
fun OnboardingScreen(
    initial: SetupChoices,
    /** Unpacking Ubuntu: 0..1, or null once it is done (or was before). */
    unpackFraction: Float?,
    unpackDetail: String,
    /** Called once, when the user leaves the welcome page. */
    onStarted: () -> Unit,
    onFinish: (SetupChoices) -> Unit,
    /** Shown from Settings: no welcome page, and a way back. */
    again: Boolean = false,
    onCancel: () -> Unit = {},
) {
    var page by remember { mutableStateOf(if (again) Page.PROFILE else Page.WELCOME) }
    var choices by remember { mutableStateOf(initial) }
    // The app takes the accent as it is chosen, so the choice can be seen.
    androidx.compose.runtime.LaunchedEffect(choices.accent) {
        UbuntuPalette.accent = Color(choices.accent.color)
    }
    var forward by remember { mutableIntStateOf(1) }
    fun go(to: Page) {
        forward = if (to.ordinal > page.ordinal) 1 else -1
        page = to
    }
    // The developer tools page is for those who code.
    fun shows(p: Page) = p != Page.DEV || Interest.CODING in choices.interests
    fun next() = Page.entries.drop(page.ordinal + 1).first { shows(it) }
    fun previous() = Page.entries.take(page.ordinal).last { shows(it) }

    val accent by animateColorAsState(Color(choices.accent.color), tween(700), label = "accent")
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(accent)
    Box(
        Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp),
        ) {
            if (page != Page.WELCOME) {
                StepDots(page.ordinal - 1, Page.entries.size - 1, Modifier.padding(top = 16.dp))
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (
                        slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 2 * forward } +
                            fadeIn(tween(320)) + scaleIn(tween(380), initialScale = 0.94f)
                        ) togetherWith
                        (slideOutHorizontally(tween(240)) { -it / 3 * forward } + fadeOut(tween(200)) + scaleOut(tween(240), targetScale = 0.96f))
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "onboarding",
            ) { shown ->
                CompositionLocalProvider(LocalStagger provides remember(shown) { Stagger() }) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 20.dp),
                ) {
                    when (shown) {
                        Page.WELCOME -> WelcomePage()
                        Page.PROFILE -> ProfilePage(choices) { choices = it }
                        Page.USES -> UsesPage(choices) { choices = it }
                        Page.LOOK -> LookPage(choices) { choices = it }
                        Page.APPS -> AppsPage(choices) { choices = it }
                        Page.DEV -> DevPage(choices) { choices = it }
                        Page.STORES -> StoresPage(choices) { choices = it }
                        Page.PHONE -> OneDevicePage()
                        Page.READY -> ReadyPage(choices, unpackFraction)
                    }
                }
                }
            }

            if (unpackFraction != null && page != Page.WELCOME) {
                UnpackBar(unpackFraction, unpackDetail)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    page == Page.WELCOME -> Spacer(Modifier.weight(1f))
                    again && page == Page.PROFILE -> TextButton("Cancel", onCancel)
                    else -> TextButton("Back") { go(previous()) }
                }
                Spacer(Modifier.weight(1f))
                val last = page == Page.READY
                PrimaryButton(
                    when (page) {
                        Page.WELCOME -> "Get started"
                        Page.READY -> if (again) "Apply" else "Start Mobuntu"
                        else -> "Next"
                    },
                    // The desktop needs Ubuntu unpacked first.
                    enabled = !last || unpackFraction == null,
                ) {
                    when {
                        page == Page.WELCOME -> { onStarted(); go(Page.PROFILE) }
                        last -> onFinish(choices)
                        else -> go(next())
                    }
                }
            }
        }
    }
        if (page == Page.READY) {
            key(page) { Confetti(accent) }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        Modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedLogo(88.dp, setupAccent)
        Spacer(Modifier.height(8.dp))
        val shine = shimmerOffset()
        Text(
            "Welcome to Mobuntu",
            style = TextStyle(
                brush = Brush.linearGradient(
                    listOf(Color.White, Color.White, lerpWhite(setupAccent), Color.White, Color.White),
                    start = androidx.compose.ui.geometry.Offset(shine * 900f - 300f, 0f),
                    end = androidx.compose.ui.geometry.Offset(shine * 900f + 300f, 120f),
                ),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.riseIn(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A full Linux desktop, in your pocket",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.riseIn(),
        )
        Spacer(Modifier.height(36.dp))
        Feature(Icons.Default.Smartphone, "In your hand", "A desktop sized for the phone's screen, with touch and a trackpad")
        Feature(Icons.Default.Computer, "At your desk", "Plug into a USB-C monitor and the phone becomes the trackpad")
        Feature(Icons.Default.Storefront, "Yours to shape", "Keep it light, or add apps from Ubuntu and Flathub")
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().riseIn().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(setupAccent.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = UbuntuPalette.UbuntuOrangeLight)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        }
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.riseIn())
    Spacer(Modifier.height(6.dp))
    Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, modifier = Modifier.riseIn())
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun ProfilePage(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PageTitle("How much Linux?", "Pick a starting point. Everything can be added or removed later.")
    SetupProfile.entries.forEach { profile ->
        val selected = choices.profile == profile
        val sample = SetupChoices(profile = profile, browser = profile != SetupProfile.LIGHT, apps = SetupChoices.defaultApps(profile))
        SelectCard(selected = selected, onClick = {
            onChange(
                choices.copy(
                    profile = profile,
                    browser = sample.browser,
                    apps = sample.apps,
                    devTools = SetupChoices.defaultDevTools(profile),
                ),
            )
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(sizeLabel(sample.downloadMb), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(profile.detail, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun LookPage(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PageTitle("Make it yours", "Ubuntu's Yaru look, in light or dark, with the colour you like.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(false, true).forEach { dark ->
            ModePreview(
                dark = dark,
                wallpaper = choices.wallpaper,
                accent = Color(choices.accent.color),
                selected = choices.dark == dark,
                onClick = { onChange(choices.copy(dark = dark)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(22.dp))
    SectionLabel("Accent colour")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Accent.entries.forEach { accent ->
            val selected = choices.accent == accent
            val grow by animateFloatAsState(if (selected) 1.18f else 1f, spring(dampingRatio = 0.4f), label = "dot")
            Box(
                Modifier
                    .riseIn()
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(accent.color))
                    .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                    .clickable { onChange(choices.copy(accent = accent)) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, contentDescription = accent.label, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    SectionLabel("Wallpaper")
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Wallpaper.entries.forEach { w ->
            val selected = choices.wallpaper == w
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WallpaperThumb(
                    w,
                    Modifier
                        .size(width = 64.dp, height = 104.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .clickable { onChange(choices.copy(wallpaper = w)) },
                )
                Spacer(Modifier.height(4.dp))
                Text(w.label, color = Color.White.copy(alpha = if (selected) 1f else 0.65f), fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
    SectionLabel("Size on the phone")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DesktopSize.entries.forEach { size ->
            val selected = choices.desktopSize == size
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                    .border(if (selected) 2.dp else 1.dp, if (selected) Color(choices.accent.color) else Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .clickable { onChange(choices.copy(desktopSize = size)) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(size.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        choices.desktopSize.detail + ". A monitor always gets a full-size desktop.",
        color = Color.White.copy(alpha = 0.65f),
        fontSize = 12.sp,
    )
}

/** A small drawing of the desktop in light or dark. */
@Composable
private fun ModePreview(dark: Boolean, wallpaper: Wallpaper, accent: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val window = if (dark) Color(0xFF2C2C2C) else Color(0xFFFAFAFA)
    val bar = if (dark) Color(0xFF242424) else Color(0xFFEDEDED)
    val line = if (dark) Color(0xFF5A5A5A) else Color(0xFFD0D0D0)
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            WallpaperThumb(wallpaper, Modifier.matchParentSize())
            Column(
                Modifier
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 20.dp)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(window),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(bar).padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(line))
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(line))
                    Spacer(Modifier.width(3.dp))
                    Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                }
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(line))
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(line))
                    Box(Modifier.width(28.dp).height(9.dp).clip(RoundedCornerShape(5.dp)).background(accent))
                }
            }
            Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.BottomCenter).background(Color(0xE0222222)))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (dark) "Dark" else "Light", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UsesPage(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PageTitle(
        "What will you use it for?",
        "Pick as many as you like. Mobuntu suggests apps for each, and the store shows them first.",
    )
    Interest.entries.filter { it.inSetup }.forEach { interest ->
        val on = interest in choices.interests
        SelectCard(selected = on, onClick = { onChange(choices.withInterest(interest, !on)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(interest.color)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(interest.icon, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(interest.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(interest.detail, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
                AnimatedVisibility(on, enter = scaleIn(spring(dampingRatio = 0.4f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Icon(Icons.Default.Check, contentDescription = "Picked", tint = Color.White)
                }
            }
        }
    }
    Text(
        "Nothing here for you? Skip it: the store has everything.",
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
    )
}

@Composable
private fun AppsPage(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PageTitle("Apps", "Suggested for what you picked, and Ubuntu's essentials. The Software store has thousands more.")
    val tick = Color(choices.accent.color)
    choices.interests.sortedBy { it.ordinal }.forEach { interest ->
        val apps = Collections.of(interest).filter { it.runsHere }
        if (apps.isEmpty()) return@forEach
        SectionLabel(interest.label)
        interest.note?.let {
            Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        apps.forEach { app ->
            val ubuntu = app.ubuntu
            val on = if (ubuntu != null) app.id in choices.apps else app.id in choices.flatpaks
            val source = if (ubuntu != null) "Ubuntu" else "Flathub"
            AppToggle(app.name, "${app.summary} · $source", interest.icon, Color(interest.color), on, tick) { checked ->
                onChange(
                    if (ubuntu != null) {
                        choices.copy(apps = if (checked) choices.apps + app.id else choices.apps - app.id)
                    } else {
                        choices.copy(
                            flatpaks = if (checked) choices.flatpaks + app.id else choices.flatpaks - app.id,
                            flathub = choices.flathub || checked,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (choices.interests.isNotEmpty()) SectionLabel("Essentials")
    val firefox = StoreCatalog.featured.first { it.installer != null }
    AppToggle(firefox.name, "The web browser", firefox.icon ?: Icons.Default.Public, Color(firefox.accent), choices.browser, Color(choices.accent.color)) {
        onChange(choices.copy(browser = it))
    }
    StoreCatalog.featured.filter { it.installer == null }.forEach { app ->
        val on = app.pkg in choices.apps
        AppToggle(app.name, app.summary, app.icon ?: Icons.Default.Inventory2, Color(app.accent), on, Color(choices.accent.color)) { checked ->
            onChange(choices.copy(apps = if (checked) choices.apps + app.pkg else choices.apps - app.pkg))
        }
    }
}

@Composable
private fun DevPage(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PageTitle(
        "For developers",
        "Popular open-source tools from GitHub, packaged by Ubuntu. Skip this if you just want a desktop.",
    )
    val all = SetupChoices.devPicks.map { it.pkg }.toSet()
    val tick = Color(choices.accent.color)
    AppToggle(
        "All of them",
        "About ${SetupChoices.devPicks.sumOf { it.mb }} MB",
        Icons.Default.DoneAll,
        Color.White.copy(alpha = 0.15f),
        choices.devTools.containsAll(all),
        tick,
    ) { checked -> onChange(choices.copy(devTools = if (checked) all else emptySet())) }
    Spacer(Modifier.height(8.dp))
    SetupChoices.devPicks.forEach { pick ->
        val on = pick.pkg in choices.devTools
        AppToggle(pick.name, "${pick.summary} · ${pick.repo}", Icons.Default.Code, Color(0xFF24292F), on, tick) { checked ->
            onChange(choices.copy(devTools = if (checked) choices.devTools + pick.pkg else choices.devTools - pick.pkg))
        }
    }
}

@Composable
private fun StoresPage(choices: SetupChoices, onChange: (SetupChoices) -> Unit) {
    PageTitle("App stores", "Where new apps come from.")
    SelectCard(selected = true, onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Apps, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ubuntu archive", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Ubuntu's archive: tens of thousands of packages", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
            Text("Always on", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
    SelectCard(selected = choices.flathub, onClick = { onChange(choices.copy(flathub = !choices.flathub)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storefront, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Flathub", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    "The newest versions of thousands of Linux apps. Each brings its own runtime, " +
                        "so the first one is a few hundred MB.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = choices.flathub,
                onCheckedChange = { onChange(choices.copy(flathub = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(choices.accent.color)),
            )
        }
    }
    val chosen = choices.flatpaks.size
    if (chosen > 0) {
        val plural = if (chosen == 1) "" else "s"
        Text(
            if (choices.flathub) "$chosen app$plural from Flathub will install with setup."
            else "With Flathub off, the $chosen Flathub app$plural you picked will not install.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ReadyPage(choices: SetupChoices, unpackFraction: Float?) {
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
        AnimatedLogo(64.dp, setupAccent, spinning = unpackFraction != null)
    }
    PageTitle(
        if (unpackFraction == null) "You're all set" else "Almost there",
        if (unpackFraction == null) "Ubuntu starts now. Your apps install in the background."
        else "Ubuntu is still unpacking. It only takes a minute or two.",
    )
    SummaryRow("Setup", choices.profile.label)
    if (choices.interests.isNotEmpty()) {
        SummaryRow("For", choices.interests.sortedBy { it.ordinal }.joinToString(", ") { it.label.lowercase() })
    }
    SummaryRow("Look", "${if (choices.dark) "Dark" else "Light"}, ${choices.accent.label.lowercase()}, ${choices.wallpaper.label}")
    SummaryRow("Size on the phone", choices.desktopSize.label)
    val apps = buildList {
        if (choices.browser) add("Firefox")
        StoreCatalog.featured.filter { it.pkg in choices.apps }.forEach { add(it.name) }
        if (choices.flathub) {
            choices.flatpaks.forEach { id -> add(Collections.all.firstOrNull { it.id == id }?.name ?: id.substringAfterLast('.')) }
        }
    }
    SummaryRow("Apps", if (apps.isEmpty()) "None yet" else apps.joinToString(", "))
    val dev = SetupChoices.devPicks.filter { it.pkg in choices.devTools }.map { it.name }
    if (dev.isNotEmpty()) SummaryRow("Developer tools", dev.joinToString(", "))
    SummaryRow("Flathub", if (choices.flathub) "On" else "Off")
    SummaryRow("To download", "about ${sizeLabel(choices.downloadMb)}")
    Spacer(Modifier.height(12.dp))
    Text(
        "Best on Wi-Fi. The desktop is usable while the rest downloads; a bar at the top shows how far it got.",
        color = Color.White.copy(alpha = 0.65f),
        fontSize = 12.sp,
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().riseIn().padding(vertical = 8.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, modifier = Modifier.width(140.dp))
        Text(value, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.6f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun SelectCard(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f), label = "press")
    val fill by animateColorAsState(if (selected) setupAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f), tween(300), label = "fill")
    val edge by animateColorAsState(if (selected) setupAccent else Color.White.copy(alpha = 0.12f), tween(300), label = "edge")
    val width by animateDpAsState(if (selected) 2.dp else 1.dp, label = "width")
    val tick = rememberTick()
    Column(
        Modifier
            .fillMaxWidth()
            .riseIn()
            .padding(bottom = 12.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.4f))))
            .border(width, edge, RoundedCornerShape(18.dp))
            .clickable(interactionSource = press, indication = null) { tick(); onClick() }
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun AppToggle(
    name: String,
    summary: String,
    icon: ImageVector,
    accent: Color,
    checked: Boolean,
    tick: Color = UbuntuPalette.UbuntuOrange,
    onChange: (Boolean) -> Unit,
) {
    val fill by animateColorAsState(if (checked) tick else Color.Transparent, tween(220), label = "tick")
    val edge by animateColorAsState(if (checked) tick else Color.White.copy(alpha = 0.4f), tween(220), label = "tickEdge")
    val pop by animateFloatAsState(if (checked) 1f else 0.85f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium), label = "pop")
    val feel = rememberTick()
    Row(
        Modifier
            .fillMaxWidth()
            .riseIn()
            .clip(RoundedCornerShape(14.dp))
            .clickable { feel(); onChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(summary, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
        }
        Box(
            Modifier
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(fill)
                .border(2.dp, edge, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // The box itself pops (pop above), and the tick with it.
            if (checked) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** A wallpaper, drawn small (and in software: the designs use blur). */
@Composable
fun WallpaperThumb(wallpaper: Wallpaper, modifier: Modifier = Modifier) {
    val picture by produceState(thumbs[wallpaper], wallpaper) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.Default) {
            val bmp = android.graphics.Bitmap.createBitmap(240, 240, android.graphics.Bitmap.Config.ARGB_8888)
            wallpaper.draw(android.graphics.Canvas(bmp), 240f, 240f)
            bmp.asImageBitmap().also { thumbs[wallpaper] = it }
        }
    }
    Box(modifier.background(Color(0xFF2C001E))) {
        picture?.let {
            Image(it, contentDescription = wallpaper.label, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

private val thumbs = java.util.concurrent.ConcurrentHashMap<Wallpaper, ImageBitmap>()

@Composable
private fun StepDots(index: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val color by animateColorAsState(
                if (i <= index) setupAccent else Color.White.copy(alpha = 0.18f), tween(400), label = "step",
            )
            val weight by animateFloatAsState(if (i == index) 2.2f else 1f, spring(dampingRatio = 0.6f), label = "stepWidth")
            Box(
                Modifier
                    .weight(weight)
                    .height(if (i == index) 5.dp else 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun UnpackBar(fraction: Float, detail: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                detail.ifBlank { "Unpacking Ubuntu…" },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text("${(fraction * 100).toInt()}%", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = UbuntuPalette.UbuntuOrange,
            trackColor = Color.White.copy(alpha = 0.15f),
        )
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.45f), label = "press")
    val shine = shimmerOffset()
    val feel = rememberTick()
    val base = setupAccent
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (enabled) {
                    Brush.linearGradient(
                        listOf(base, base, lerpWhite(base), base, base),
                        start = androidx.compose.ui.geometry.Offset(shine * 500f - 150f, 0f),
                        end = androidx.compose.ui.geometry.Offset(shine * 500f + 150f, 60f),
                    )
                } else {
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.15f)))
                },
            )
            .clickable(enabled = enabled, interactionSource = press, indication = null) { feel(); onClick() }
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TextButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

private fun sizeLabel(mb: Int): String = if (mb >= 1000) "%.1f GB".format(mb / 1000f) else "$mb MB"

/** A lighter, shinier version of a colour, for highlights. */
private fun lerpWhite(c: Color): Color = androidx.compose.ui.graphics.lerp(c, Color.White, 0.6f)

/** A light tick under the finger, for when something is picked. */
@Composable
private fun rememberTick(): () -> Unit {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    }
}
