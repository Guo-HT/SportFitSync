# Compose Multiplatform (iOS) Port Assessment — UI Layer

**Scope:** `app/src/main/java/com/sportfit/sync/ui/**` (19 files, 5,510 LOC) +
`app/src/main/java/com/sportfit/sync/MainActivity.kt` (61 LOC) +
`app/src/main/java/com/sportfit/sync/SportFitApp.kt` (31 LOC) +
`app/src/main/res/**` (15 files) → **5,602 LOC / 20 Kotlin files / 15 res files**.

**Verdict: 🟡 MODERATE** — ~61% of UI LOC is API-identical and ports near-unchanged; the
Android-only surface is unusually small and concentrated in 4 places; but three structural
blockers (module split, multiplatform `navigation-compose` maturity, Strava OAuth WebView)
keep it out of "Easy".

**Rough effort: 8–14 person-days for a shippable iOS UI (≈11 pd central estimate).**
UI-only slice excluding the Strava OAuth rework: ≈ 8 pd.

---

## 1. Inventory of Android-only Compose / platform APIs

### 1.1 Complete list of non-multiplatform API usage sites

| # | API | file:line | Notes |
|---|---|---|---|
| 1 | `androidx.compose.ui.viewinterop.AndroidView` | `ui/screens/StravaAuthScreen.kt:34` (import), **`:124`** (call) | The single `AndroidView` site in the whole UI layer. Factory block `:126-157` |
| 2 | ↳ `android.webkit.WebView` | `ui/screens/StravaAuthScreen.kt:5` (import), **`:127`** `WebView(context)` | |
| 3 | ↳ `android.webkit.WebViewClient` | `ui/screens/StravaAuthScreen.kt:6` (import), **`:130`** `webViewClient = object : WebViewClient()` | Override `shouldOverrideUrlLoading` at `:131-141` (both the `WebResourceRequest` and the deprecated `String` overload) |
| 4 | ↳ `android.webkit.WebResourceRequest` | `ui/screens/StravaAuthScreen.kt:4` (import), **`:133`** | |
| 5 | ↳ `android.annotation.SuppressLint("SetJavaScriptEnabled")` | `ui/screens/StravaAuthScreen.kt:3` (import), **`:49`** | |
| 6 | ↳ `WebViewHolder` holder class | `ui/screens/StravaAuthScreen.kt:189-191`, used `:61,155,169` | Plain (non-State) holder so the factory doesn't trigger recomposition |
| 7 | `androidx.compose.ui.platform.LocalContext` | `ui/screens/SyncScreen.kt:60` (import), **`:120`** `val context = LocalContext.current` | Only `LocalContext` use in the codebase |
| 8 | `androidx.activity.compose.rememberLauncherForActivityResult` | `ui/screens/SyncScreen.kt:5` (import), **`:123`** | |
| 9 | ↳ `androidx.activity.result.contract.ActivityResultContracts.RequestPermission` | `ui/screens/SyncScreen.kt:6` (import), **`:124`** | |
| 10 | ↳ `notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)` | `ui/screens/SyncScreen.kt:133` | |
| 11 | `androidx.core.content.ContextCompat.checkSelfPermission` | `ui/screens/SyncScreen.kt:62` (import), **`:129-132`** | |
| 12 | ↳ `android.content.pm.PackageManager.PERMISSION_GRANTED` | `ui/screens/SyncScreen.kt:4` (import), **`:132`** | |
| 13 | ↳ `android.Manifest.permission.POST_NOTIFICATIONS` | `ui/screens/SyncScreen.kt:3` (import), **`:131, :133`** | |
| 14 | ↳ `android.os.Build.VERSION.SDK_INT < 33` | `ui/screens/SyncScreen.kt:128` | |
| 15 | `com.sportfit.sync.BuildConfig` (AGP-generated) | `ui/screens/SyncScreen.kt:63` (import), **`:114`** `Platform.pickable(includeDemo = BuildConfig.DEBUG)` | Needs an `expect/actual` `isDebug` flag in common code |
| 16 | `androidx.compose.ui.res.painterResource` + `com.sportfit.sync.R` | `ui/components/Design.kt:39` (import), **`:43`** (import R), `:74`, `:151`, `:189`, `:362` | 4 call sites |
| 17 | ↳ `R.drawable.*` references | `ui/screens/AccountsScreen.kt:95`; `ui/screens/ActivityPickerScreen.kt:302`; `ui/screens/HistoryScreen.kt:84`; `ui/screens/SyncScreen.kt:237, :322`; `ui/components/Design.kt:74, :151` | 5 drawables referenced from 8 sites |
| 18 | `androidx.lifecycle.ViewModel` | `ui/AppViewModel.kt:3` (import), **`:65`** `class AppViewModel(...) : ViewModel()` | |
| 19 | `androidx.lifecycle.viewModelScope` | `ui/AppViewModel.kt:5` (import); **14 call sites**: `:73, :76, :80, :83, :88, :177, :216, :289, :305, :340, :372, :474, :510, :530, :563, :572, :598, :611, :626, :644, :665, :731, :735, :743` | |
| 20 | `androidx.lifecycle.ViewModelProvider.Factory` | `ui/AppViewModel.kt:4` (import), **`:836-840`** `override fun <T : ViewModel> create(modelClass: Class<T>): T` | ⚠️ multiplatform artifact uses a `KClass<T>` signature — must be rewritten |
| 21 | `androidx.activity.viewModels` | `MainActivity.kt:7` (import), **`:30`** `internal val appViewModel: AppViewModel by viewModels { ... }` | No iOS equivalent; use `viewModel {}` / manual construction |
| 22 | `androidx.lifecycle.Lifecycle` / `lifecycleScope` / `repeatOnLifecycle` | `MainActivity.kt:8-10` (imports), **`:52-59`** | `repeatOnLifecycle` is Android-only |
| 23 | `androidx.activity.ComponentActivity` / `setContent` / `enableEdgeToEdge` | `MainActivity.kt:4-6` (imports), `:18`, `:34`, `:38-42` | → `ComposeUIViewController` |
| 24 | `android.view.Window` / `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` | `KeepScreenOn.kt:3-4` (import), **`:26-28`**; called `MainActivity.kt:57` | → `UIApplication.shared.isIdleTimerDisabled` |
| 25 | `android.app.Application` | `SportFitApp.kt:3` (import), **`:15`** `class SportFitApp : Application()` | |
| 26 | `java.text.SimpleDateFormat` / `java.util.Date` / `java.util.Locale` / `java.util.concurrent.TimeUnit` | `ui/Format.kt:3-6` (imports); used `:11-13`, `:16`, `:19`, `:22`, `:26-27`, `:36-38` | `java.util.*` does not exist on Kotlin/Native |
| 27 | `androidx.navigation.*` (navigation-compose 2.9.0) | `ui/AppRoot.kt:22-29` (imports); `:63` `rememberNavController()`, `:64` `currentBackStackEntryAsState()`, `:86` `hierarchy`, `:101` `findStartDestination()`, `:117-188` `NavHost`/`composable`/`navArgument`/`NavType` | → `org.jetbrains.androidx.navigation` |

### 1.2 Positive findings — what is **NOT** used (this is why the port is tractable)

Verified by exhaustive grep over `ui/`, `MainActivity.kt`, `SportFitApp.kt` — **zero hits**:

- **WindowInsets**: `WindowInsets`, `statusBarsPadding`, `navigationBarsPadding`, `imePadding`, `displayCutoutPadding`, `windowInsetsPadding` → **0 hits**. The app relies purely on M3 `Scaffold`'s default `contentWindowInsets`.
- **Clipboard**: `LocalClipboardManager`, `LocalClipboard` → 0 hits.
- **Other platform locals**: `LocalUriHandler`, `LocalSoftwareKeyboardController`, `LocalDensity`, `LocalLayoutDirection`, `LocalConfiguration`, `LocalView`, `LocalLifecycleOwner`, `LocalSavedStateRegistryOwner`, `LocalTextSelection`, `LocalAutofill` → 0 hits.
- **Android-only M3 features**: `PullToRefresh`, `DatePicker`, `DateRangePicker`, `TimePicker`, `Tooltip`, `PlainTooltip`, `ModalBottomSheet`, `BottomSheetScaffold`, `TopAppBar`, `CenterAlignedTopAppBar`, `DropdownMenu`, `ExposedDropdownMenu`, `SegmentedButton`, `SwipeToDismiss`, `FloatingActionButton`, `Badge`, `Carousel`, `TabRow`, `HorizontalDivider`, `TextField` (only `OutlinedTextField` is used) → 0 hits. **No CMP workaround needed for any of these.**
- **Other**: `AndroidViewBinding`, `ComposeView`, `AbstractComposeView`, `Accompanist*`, `CameraX`, `PagingCompose`, `Hilt` → 0 hits.
- `collectAsStateWithLifecycle` → **0 hits** (the app uses plain `androidx.compose.runtime.collectAsState`, e.g. `ui/AppRoot.kt:67-68`, `ui/screens/SyncScreen.kt:100-107`). **This removes the `lifecycle-runtime-compose` dependency from the UI layer entirely.**
- `viewModel()` composable → **0 hits** in the UI layer. Every screen takes `viewModel: AppViewModel` as a plain constructor parameter (`ui/AppRoot.kt:62, :122, :131, :137, :144, :150, :163, :170, :176, :182`), so the CMP port needs no DI framework and no `viewModel()` composable — the VM is built once and threaded through.

---

## 2. Material3 / Material Icons / navigation-compose → CMP mapping

### 2.1 Material3 — full 1:1 mapping available

Every `androidx.compose.material3.*` symbol used maps to the identically-named
`org.jetbrains.compose.material3.*` symbol. Complete list of M3 symbols in use (21 distinct):

`MaterialTheme` · `Typography` · `Shapes` · `lightColorScheme` · `darkColorScheme` ·
`Scaffold` · `NavigationBar` · `NavigationBarItem` · `SnackbarHost` · `SnackbarHostState` ·
`Surface` · `Card` · `CardDefaults` · `Icon` · `IconButton` · `Text` ·
`AlertDialog` · `Button` · `ButtonDefaults` · `OutlinedButton` · `TextButton` ·
`OutlinedTextField` · `FilterChip` · `FilterChipDefaults` · `Checkbox` · `Switch` · `Slider` ·
`CircularProgressIndicator` · `LinearProgressIndicator`

All present in `org.jetbrains.compose.material3`. **No Android-only M3 feature is used, so no
workarounds are required.**

⚠️ **Hard version floor:** `ui/theme/Theme.kt:51-55` and `:95-99` set the
`surfaceContainerLowest / surfaceContainerLow / surfaceContainer / surfaceContainerHigh /
surfaceContainerHighest` roles. These were added in Material3 1.2 / Compose BOM 2024.09.
CMP ≤1.6.x is built on Compose 1.6 and **does not have them** → the port requires **CMP ≥ 1.7**.

### 2.2 Behavioral differences on iOS for APIs that *are* supported

| API | Site | iOS difference | Impact |
|---|---|---|---|
| `FilterChip` / `FilterChipDefaults.filterChipColors` | `ui/components/Common.kt:59-79` | `selectedContainerColor` alpha-blend over the M3 surface renders slightly differently; no ripple | Cosmetic only |
| `Slider` | `ui/screens/SettingsScreen.kt:315` | Compose-drawn thumb/track on iOS; Apple HIG expects a 44pt hit target (M3 `Slider` is ~44×44dp on Android, narrower on iOS) | Add `Modifier.minimumInteractiveComponentSize()` or explicit height |
| `Switch` | `ui/screens/SettingsScreen.kt:364` | M3 `Switch` is 52×32dp — below the iOS 44pt minimum height | Bump to 44dp or accept |
| `AlertDialog` (×5 sites) | `ui/screens/SyncScreen.kt:41`, `ui/screens/CacheScreen.kt:29`, `ui/screens/AccountsScreen.kt:22`, `ui/screens/ActivityPickerScreen.kt:21`, `ui/components/Common.kt:21` | Android puts the affirmative action left; iOS convention is right. CMP renders the Compose dialog, so the **button order stays Android-style** | Reorder `confirmButton`/`dismissButton` for iOS, or accept |
| `SnackbarHost` / `showSnackbar` | `ui/AppRoot.kt:66, :72, :78` | Works on iOS (rounded box, no swipe-to-dismiss) | Cosmetic |
| `OutlinedTextField` + `KeyboardOptions(keyboardType = KeyboardType.Number)` | `ui/components/Common.kt:222` | `KeyboardType.Number` → iOS number pad. Other `KeyboardType`s are ignored on iOS, but only `Number` and `PasswordVisualTransformation` (`:251`) are used → both supported | None |
| `Modifier.clickable` | `ui/screens/HistoryScreen.kt:110`, `ui/screens/SettingsScreen.kt:246, :404` | Android ripple → iOS fade highlight | None |
| `FlowRow` + `@OptIn(ExperimentalLayoutApi::class)` | `ui/screens/SyncScreen.kt:12, :92, :271, :306`; `ui/screens/SettingsScreen.kt:8, :65, :120` | FlowRow is stable in Compose Foundation 1.7 → available in CMP ≥1.7. The `ExperimentalLayoutApi` opt-in still compiles (annotation class retained) | None; can drop the `@OptIn` |
| `Card` elevation | `ui/components/Common.kt:116` `cardElevation(defaultElevation = 0.dp)` | App uses 0 elevation everywhere → iOS shadow model differences are moot | None |

### 2.3 Material Icons

- `androidx.compose.material:material-icons-extended:1.7.8` (`app/build.gradle.kts:91`) →
  `org.jetbrains.compose.material:material-icons-extended` (CMP ships a mirror of the Android artifact).
- **42 distinct icons** used, of which **6 come from the `automirrored` package**
  (`ArrowBack`, `ArrowForward`, `CompareArrows`, `DirectionsBike`, `DirectionsRun`, `FactCheck`).
  `automirrored` was introduced in Compose Material Icons 1.6 → available in CMP ≥1.7.
- One unusual name worth double-checking: `Icons.Filled.OutlinedFlag` (`ui/components/Common.kt:18`, used at `:83` for the Garmin badge) — the "outlined flag" glyph lives in the **Filled** group and does exist in the extended set.
- Import change is a one-line package rename per file: `androidx.compose.material.icons.*` → `org.jetbrains.compose.material.icons.*`.

### 2.4 navigation-compose → `org.jetbrains.androidx.navigation`

`androidx.navigation:navigation-compose:2.9.0` (`app/build.gradle.kts:93`) →
`org.jetbrains.androidx.navigation:navigation-compose` (JetBrains multiplatform port, 2.8/2.9 lineage).

Every API used in `ui/AppRoot.kt` exists in the multiplatform artifact with the same signature:
`rememberNavController` (`:63`), `currentBackStackEntryAsState` (`:64`),
`NavDestination.hierarchy` (`:86`), `NavGraph.findStartDestination` (`:101`),
`popUpTo(id) { inclusive = true }` + `launchSingleTop` (`:101-104`),
`NavHost`/`composable`/`navArgument`/`NavType.StringType`/`NavType.LongType` (`:117-188`).

> ⚠️ **This is the single largest technical risk in the UI port.** The multiplatform navigation
> artifact was, until recently, published in an alpha/beta line (2.8.0-alpha* / 2.9.0-beta*) and
> its API surface has churned between releases. The app only has **9 routes**
> (`ui/AppRoot.kt:49-59` + 3 tab routes at `:44-46`), so a **fallback of a ~100-LOC hand-rolled
> navigation state holder** is cheap insurance if the artifact misbehaves.
> *Verify the exact artifact version and API against the CMP release you pin.*

---

## 3. Theme / design system — Android-specific theming audit

### 3.1 `ui/theme/Theme.kt` (189 LOC) — **100% portable**

| Check | Result |
|---|---|
| Dynamic color | ❌ **Not used.** No `dynamicLightColorScheme` / `dynamicDarkColorScheme`, no `Build.VERSION.SDK_INT >= 31` check. Static `lightColorScheme` (`:22-63`) / `darkColorScheme` (`:66-107`) only. **No iOS equivalent needed.** |
| `android:` theme attrs in Compose code | ❌ None. The `android:` attrs exist only in `res/values*/themes.xml` (see §4) |
| `isSystemInDarkTheme()` | ✅ `:3` (import), `:175` — available in CMP (`org.jetbrains.compose.foundation.isSystemInDarkTheme`), maps to iOS `UITraitCollection.userInterfaceStyle` |
| `CompositionLocalProvider` + `staticCompositionLocalOf` | ✅ `:12, :147, :171, :178-181` — portable |
| `Shapes` / `RoundedCornerShape` | ✅ `:4, :6, :110-116` — portable |
| Custom `HeroPalette` / `SurfacePalette` data classes | ✅ `:124-137, :157-168` — plain `@Immutable data class`, portable |

### 3.2 `ui/theme/Type.kt` (120 LOC) — portable, with two caveats

- **No `res/font` directory exists at all.** Every style uses `FontFamily.Default`
  (`:22, :35, :48, :60, :68, :77, :85, :92, :99, :108`). Android → Roboto, iOS → San Francisco.
  **Same code, different rendering** → needs a visual QA pass, no code change.
- ⚠️ `fontFeatureSettings = "tnum"` (`:100`, in `NumericTextStyle`) — tabular figures, the
  mechanism that stops stat numbers from jittering (documented at `:97-99`).
  CMP supports `fontFeatureSettings` on iOS via CoreText in recent versions; **verify against the
  pinned CMP version** — if unsupported, the tabular-figure behaviour is lost (cosmetic, 1-line fallback).
- `letterSpacing` in `sp` (e.g. `:36, :45, :96, :107`) — CMP supports `TextUnit.sp` on iOS. ✓

### 3.3 `ui/theme/Color.kt` (65 LOC) — 100% portable. Pure `Color(0xFF…)` constants. ✓

### 3.4 `ui/components/Design.kt` (469 LOC) — **the only Android-specific theming path**

`androidx.compose.ui.res.painterResource` (`:39`) + `com.sportfit.sync.R` (`:43`) resolve
**Android** drawable resources. Sites: `:74` (`ic_decor_rings`, tinted via `ColorFilter.tint` at
`:79` and `graphicsLayer { alpha = 0.16f }` at `:84`), `:151` (`ic_hero_sync` default argument),
`:189` and `:362` (`painterResource(illustration)`).

The tint/alpha/ContentScale modifiers themselves are 100% portable — only the **resource
resolution** needs to change. Two options:

- **(A) CMP Resources (recommended):** move the 6 XML vector drawables into
  `commonMain/composeResources/drawable/` and swap `painterResource(R.drawable.x)` →
  `painterResource(Res.drawable.x)` (`org.jetbrains.compose.resources`).
  *CMP ≥ 1.7 supports Android XML VectorDrawable in the common `composeResources/drawable`
  folder — verify this against the pinned version; if unsupported, fall back to (B).*
- **(B) Pure Kotlin:** convert each drawable to a hand-written `ImageVector` (they are simple —
  4-7 `<path>` elements each, plain `pathData`, `strokeColor`/`fillColor`, no gradients, no
  `<group>` transforms except in `ic_launcher_foreground.xml`). Fully deterministic, zero
  resource-pipeline risk, but loses `ColorFilter.tint` unless you recolour the `ImageVector`
  paths.

The `illustration: Int` parameter type in `HeroCard` (`:151`) and `EmptyState` (`:350`) must
change type: `Int` → `DrawableResource` (option A) or `ImageVector` (option B). That is a
**2-signature change** affecting 5 call sites (`ui/screens/AccountsScreen.kt:95`,
`ui/screens/ActivityPickerScreen.kt:302`, `ui/screens/HistoryScreen.kt:84`,
`ui/screens/SyncScreen.kt:237, :322`) plus `ui/components/Design.kt:74, :151`.

---

## 4. Resources inventory & iOS equivalents

`app/src/main/res` contains **15 files** in 5 folders: `drawable/` (6), `mipmap-anydpi-v26/` (1),
`values/` (3), `values-night/` (1), `xml/` (2). **There is no `font/`, no `raw/`, no `anim/`.**

| res file | Used by | iOS equivalent |
|---|---|---|
| `drawable/ic_decor_rings.xml` | `ui/components/Design.kt:74` | `composeResources/drawable/ic_decor_rings.xml` → `Res.drawable.ic_decor_rings`; or `ImageVector` |
| `drawable/ic_empty_activities.xml` | `ui/screens/SyncScreen.kt:237`, `ui/screens/ActivityPickerScreen.kt:302` | same |
| `drawable/ic_empty_accounts.xml` | `ui/screens/AccountsScreen.kt:95` | same |
| `drawable/ic_empty_history.xml` | `ui/screens/HistoryScreen.kt:84`, `ui/screens/SyncScreen.kt:322` | same |
| `drawable/ic_hero_sync.xml` | `ui/components/Design.kt:151` (default arg), `:189` | same |
| `drawable/ic_launcher_foreground.xml` | `res/mipmap-anydpi-v26/ic_launcher.xml:4` | iOS has **no adaptive icons**. → `Assets.xcassets/AppIcon.appiconset` (1024×1024 PNG). The `<group scaleX=4.0 translateX=6>` wrapper (`:6-9`) must be flattened into the PNG |
| `drawable/ic_notification.xml` | `sync/SyncService.kt:102`, `work/SyncWorker.kt:85` (notification small icon) | iOS notifications have **no small-icon slot**. Either drop it or keep `ic_notification` in an Android-only source set |
| `mipmap-anydpi-v26/ic_launcher.xml` | `AndroidManifest.xml:19` (`android:icon`) | `Info.plist` → `CFBundleIcons` / `Assets.xcassets`. **No `<adaptive-icon>` / `<monochrome>` concept on iOS** (monochrome → iOS 18 "Dark/Tinted App Icon" via `AppIcon` variant) |
| `values/colors.xml` (`ic_launcher_background` = `#1B6EF3`) | `res/mipmap-anydpi-v26/ic_launcher.xml:3` | No equivalent — bake into the AppIcon PNG |
| `values/strings.xml` (`app_name` = 运动同步) | `AndroidManifest.xml:20` (`android:label`) | `Info.plist` → `CFBundleDisplayName` / `CFBundleName`. Optionally also `composeResources/values/strings.xml` + `stringResource(Res.string.app_name)` for in-app use. **The entire UI is hard-coded Chinese strings inline** (e.g. `ui/AppRoot.kt:44-46, :73, :157, :200`), so `strings.xml` is *only* the launcher label — minimal iOS work |
| `values/themes.xml` (`Theme.SportFitSync`, parent `android:Theme.Material.Light.NoActionBar`; `windowBackground` `#FFF7F9FC`; `statusBarColor`/`navigationBarColor` = transparent) | `AndroidManifest.xml:21, :29` | **No XML themes on iOS.** The window background is already painted by Compose (`AppBackground` gradient, `ui/components/Design.kt:62-71`), so the Android `windowBackground` is effectively a launch-screen color → iOS **LaunchScreen.storyboard** background color. Transparent status/nav bars → `Info.plist` `UIStatusBarStyle` + `UIViewControllerBasedStatusBarAppearance` |
| `values-night/themes.xml` (dark variant, `windowBackground` `#FF101418`) | same | `Info.plist` `UIUserInterfaceStyle` = `Automatic`; the Compose theme already follows the system via `isSystemInDarkTheme()` (`ui/theme/Theme.kt:175`) |
| `xml/network_security_config.xml` | `AndroidManifest.xml:22` | **ATS in `Info.plist`**: `NSAppTransportSecurity` → `NSAllowsArbitraryLoads = false` + `NSExceptionDomains` for `www.onelap.cn` and `u.onelap.cn` (cleartext HTTP allowed for exactly those two hosts — matching the Android config at `:15-18`) |
| `xml/data_extraction_rules.xml` | `AndroidManifest.xml:17` (`android:dataExtractionRules`) | iOS has no equivalent file. Apply `URLResourceValues.isExcludedFromBackup = true` to the app's data directory, and/or move secrets to the **Keychain** (the Android app uses Keystore — see the user-facing copy at `ui/screens/SettingsScreen.kt:214`, `ui/screens/AccountsScreen.kt:116`, `ui/screens/CacheScreen.kt:196`, which must be re-worded for iOS) |

**Also in scope (AndroidManifest.xml, drives UI-visible behavior):**
`POST_NOTIFICATIONS` permission → **no iOS equivalent** (see §6.4); `INTERNET` /
`ACCESS_NETWORK_STATE` → n/a on iOS; `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` /
`WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` → no iOS equivalent (background sync needs
`BGTaskScheduler`, a product decision outside the UI layer).

---

## 5. LOC: near-unchanged vs. needs rework

Totals: **5,602 LOC** (incl. comments/blanks) / **~4,654 LOC** of code (excl. comments/blanks).

### Tier A — ports essentially unchanged (import swap / 1-line resource ref): **3,399 LOC (60.7%)**

| File | LOC | code | Change needed |
|---|---|---|---|
| `ui/screens/ActivityPickerScreen.kt` | 633 | 503 | import swap + `:302` `R.drawable` |
| `ui/screens/SyncScreen.kt` | 530 | 476 | → Tier B (permission + BuildConfig) |
| `ui/components/Design.kt` | 469 | 401 | → Tier B (resource plumbing) |
| `ui/screens/SettingsScreen.kt` | 437 | 387 | import swap only |
| `ui/screens/CacheScreen.kt` | 365 | 335 | import swap only |
| `ui/screens/AccountsScreen.kt` | 289 | 254 | import swap + `:95` `R.drawable` |
| `ui/screens/JobDetailScreen.kt` | 287 | 243 | import swap only |
| `ui/components/Common.kt` | 282 | 248 | import swap only |
| `ui/screens/HistoryScreen.kt` | 271 | 202 | import swap + `:84` `R.drawable` |
| `ui/screens/AccountEditScreen.kt` | 232 | 207 | import swap only |
| `ui/AppRoot.kt` | 199 | 175 | import swap only (`androidx.navigation` → `org.jetbrains.androidx.navigation`) |
| `ui/theme/Theme.kt` | 189 | 138 | import swap only |
| `ui/theme/Type.kt` | 120 | 100 | import swap only |
| `ui/screens/StravaAuthScreen.kt` | 221 | 190 | → Tier C (full rewrite) |
| `ui/theme/Color.kt` | 65 | 31 | import swap only |
| `ui/components/Status.kt` | 30 | 16 | **zero changes** (pure Kotlin) |

### Tier A breakdown (the 13 files that are truly import-swap only)

`ui/AppRoot.kt` (199) · `ui/components/Common.kt` (282) · `ui/components/Status.kt` (30) ·
`ui/screens/AccountEditScreen.kt` (232) · `ui/screens/AccountsScreen.kt` (289) ·
`ui/screens/ActivityPickerScreen.kt` (633) · `ui/screens/CacheScreen.kt` (365) ·
`ui/screens/HistoryScreen.kt` (271) · `ui/screens/JobDetailScreen.kt` (287) ·
`ui/screens/SettingsScreen.kt` (437) · `ui/theme/Color.kt` (65) · `ui/theme/Theme.kt` (189) ·
`ui/theme/Type.kt` (120)
= **3,399 LOC**, of which **3,268 LOC have literally zero Android-specific code** (only 3 files
carry a single `R.drawable` reference each).

### Tier B — needs targeted rework (< 20% of file touched): **1,982 LOC (35.4%)**

| File | LOC | code | What must change |
|---|---|---|---|
| `ui/AppViewModel.kt` | 843 | 653 | `androidx.lifecycle.*` → `org.jetbrains.androidx.lifecycle.*`; rewrite `ViewModelProvider.Factory` (`:836-840`) for the multiplatform `create(modelClass: KClass<T>)` signature; set up `Dispatchers.Main` on iOS for `viewModelScope` (24 call sites). Body is otherwise pure Kotlin + coroutines + Room. Also depends on `AppContainer` (`ui/AppViewModel.kt:6, :65`) which holds an Android `Context` (`AppContainer.kt:26, :28, :36, :41, :58, :63`) — out of `ui/` scope but blocking |
| `ui/components/Design.kt` | 469 | 401 | `painterResource`/`R` → CMP resources (4 sites); `illustration: Int` → `DrawableResource`/`ImageVector` (2 signatures) |
| `ui/screens/SyncScreen.kt` | 530 | 476 | notification-permission block (`:120-134`, 15 LOC) → delete/no-op; `BuildConfig.DEBUG` (`:114`) → expect/actual; 2 `R.drawable` refs (`:237, :322`) |
| `ui/Format.kt` | 48 | 38 | `java.text.SimpleDateFormat`/`java.util.*` → `kotlinx-datetime` (or `expect/actual`) |
| `MainActivity.kt` | 61 | 39 | replace with `MainViewController.kt` (`ComposeUIViewController`); `repeatOnLifecycle` → `LaunchedEffect`; `KeepScreenOn` → `UIApplication.shared.isIdleTimerDisabled` |
| `SportFitApp.kt` | 31 | 18 | `android.app.Application` → iOS app-delegate hook (or drop into the CMP entry point) |

### Tier C — full rewrite: **221 LOC (3.9%)**

| File | LOC | code | What must change |
|---|---|---|---|
| `ui/screens/StravaAuthScreen.kt` | 221 | 190 | The `AndroidView { WebView(...) }` block (`:124-158`) has **no 1:1 iOS counterpart**. Options: **(a)** `UIKitView { WKWebView }` + `WKNavigationDelegate.decidePolicyFor` to intercept the redirect — closest to current UX, ~3 pd; **(b)** `ASWebAuthenticationSession` — less code but changes the UX to a system browser sheet **and requires changing `StravaOAuth.REDIRECT_URI` from `http://localhost/exchange_token` to a custom scheme** (e.g. `sportfitsync://exchange_token`) + `CFBundleURLTypes` in `Info.plist` + a change to the Strava app configuration in strava.com. **This ripples outside `ui/` into `platform/strava/StravaOAuth.kt`** |

**Rework ratio: 39.3% of LOC (Tier B 35.4% + Tier C 3.9%), but ~96% of that rework is
concentrated in 8 files.** The *architectural* risk is far lower than the raw percentage suggests.

---

## 6. Lifecycle / ViewModel usage

### 6.1 `androidx.lifecycle` surface in the UI layer

| Symbol | Site | CMP replacement | Notes |
|---|---|---|---|
| `androidx.lifecycle.ViewModel` | `ui/AppViewModel.kt:3, :65` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel` | Drop-in |
| `androidx.lifecycle.viewModelScope` | `ui/AppViewModel.kt:5` + 24 call sites | `org.jetbrains.androidx.lifecycle.viewModelScope` | Drop-in, but on iOS needs `Dispatchers.Main` to be configured (CMP's `ComposeUIViewController` provides it; a bare KMP entry point needs `Dispatchers.setMain`) |
| `androidx.lifecycle.ViewModelProvider.Factory` | `ui/AppViewModel.kt:4, :836-840` | `org.jetbrains.androidx.lifecycle.ViewModelProvider.Factory` | ⚠️ **Signature change**: the multiplatform interface's primary method is `create(modelClass: KClass<T>): T`. The current `create(modelClass: Class<T>): T` is the Android-only overload. **Must be rewritten.** |
| `androidx.lifecycle.Lifecycle` / `lifecycleScope` / `repeatOnLifecycle` | `MainActivity.kt:8-10, :52-59` | `org.jetbrains.androidx.lifecycle:lifecycle-runtime` (has `Lifecycle`/`lifecycleScope`); `repeatOnLifecycle` is Android-only | Simplest iOS fix: drive the keep-awake from a `LaunchedEffect` in Compose instead |
| `androidx.activity.viewModels` | `MainActivity.kt:7, :30` | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` → `viewModel { }` | **Not actually needed**: every screen takes the VM as a parameter (`ui/AppRoot.kt:62, :122, :131, :137, :144, :150, :163, :170, :176, :182`) — construct it once in the CMP root and thread it through, exactly as today |
| `androidx.lifecycle:lifecycle-runtime-compose` | declared `app/build.gradle.kts:83` | not needed on iOS | **`collectAsStateWithLifecycle` is never used** — zero hits across the codebase. Plain `collectAsState` is portable as-is |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | declared `app/build.gradle.kts:84` | optional | The `viewModel()` composable is never used in the UI layer |

### 6.2 `androidx.navigation` surface

See §2.4 — `ui/AppRoot.kt:22-29` is the only file; the whole nav graph is 9 routes.

### 6.3 `androidx.activity` surface

`MainActivity.kt:4-7` — `ComponentActivity`, `setContent`, `enableEdgeToEdge`, `viewModels`.
All Android-only; the CMP replacement is `ComposeUIViewController { AppRoot(...) }`.

### 6.4 Other Android-only runtime surfaces reachable from the UI

- **Notification runtime permission** — `ui/screens/SyncScreen.kt:120-134`. iOS has **no runtime
  notification permission**; authorization is requested up-front (or not at all). The entire
  `ensureNotificationPermission()` function (`:127-134`) becomes dead code on iOS.
- **`BuildConfig.DEBUG`** — `ui/screens/SyncScreen.kt:114`. Needs a common `expect val isDebug: Boolean`.
- **WebView OAuth** — see §5 Tier C.
- **Keystore-backed credential copy** — user-facing strings at `ui/screens/SettingsScreen.kt:214`,
  `ui/screens/AccountsScreen.kt:116`, `ui/screens/CacheScreen.kt:196` mention "Android Keystore"
  and must be re-worded ("iOS Keychain").

---

## 7. Concrete work items & effort

| # | Work item | Files | Est. (pd) |
|---|---|---|---|
| 1 | **Split the Gradle module into KMP.** `app/build.gradle.kts:1-7` is `com.android.application` only; the entire `ui/` package lives inside it. Create `:composeApp` with `commonMain` / `androidMain` / `iosMain`, move `ui/`, `domain/`, `platform/` (the pure-Kotlin parts) to `commonMain`; keep `data/`, `work/`, `sync/SyncService.kt`, `MainActivity.kt`, `SportFitApp.kt` in `androidMain` | `app/build.gradle.kts`, `settings.gradle.kts` | **1.0** |
| 2 | **Import sweep.** `androidx.compose.material3` → `org.jetbrains.compose.material3`; `androidx.compose.material.icons` → `org.jetbrains.compose.material.icons`; `androidx.navigation` → `org.jetbrains.androidx.navigation`; `androidx.lifecycle` → `org.jetbrains.androidx.lifecycle`; `androidx.compose.ui.res.painterResource` → `org.jetbrains.compose.resources.painterResource` | 19 files | **0.5** |
| 3 | **Resource migration.** Move 6 XML vector drawables → `commonMain/composeResources/drawable/`; swap 8 `R.drawable.*` call sites → `Res.drawable.*`; change 2 `illustration: Int` signatures | `ui/components/Design.kt` (4 sites), `ui/screens/SyncScreen.kt:237,322`, `ui/screens/AccountsScreen.kt:95`, `ui/screens/ActivityPickerScreen.kt:302`, `ui/screens/HistoryScreen.kt:84` | **0.5** |
| 4 | **Notification permission + debug flag.** Delete `ensureNotificationPermission()` (`ui/screens/SyncScreen.kt:127-134`); replace `BuildConfig.DEBUG` (`:114`) with an `expect/actual` | `ui/screens/SyncScreen.kt` | **0.25** |
| 5 | **Date formatting.** `java.text.SimpleDateFormat`/`java.util.*` → `kotlinx-datetime` (`ui/Format.kt:3-13`) | `ui/Format.kt` | **0.25** |
| 6 | **Multiplatform ViewModel.** Swap lifecycle imports; rewrite the factory for the `KClass` signature (`ui/AppViewModel.kt:836-840`); ensure `Dispatchers.Main` is available on iOS | `ui/AppViewModel.kt` | **0.5** |
| 7 | **iOS entry point.** New `MainViewController.kt` (`ComposeUIViewController`); delete/keep-behind `MainActivity.kt`; `KeepScreenOn` → `UIApplication.shared.isIdleTimerDisabled`; drop `SportFitApp` (or move to an iOS app delegate) | `MainActivity.kt`, `SportFitApp.kt`, `KeepScreenOn.kt` | **0.75** |
| 8 | **Safe-area / window insets.** The UI has **zero** `WindowInsets` usage and relies on `enableEdgeToEdge()` + M3 `Scaffold`'s default `contentWindowInsets` (`ui/AppRoot.kt:77`). On iOS, verify the notch / home-indicator / dynamic-island handling on the `NavigationBar` and the `AppBackground` gradient (`ui/components/Design.kt:62-71`); add `WindowInsets.safeDrawing` where needed | `ui/AppRoot.kt`, `ui/components/Design.kt` | **0.75** |
| 9 | **Strava OAuth rewrite** ⚠️ largest item | `ui/screens/StravaAuthScreen.kt` (221), `platform/strava/StravaOAuth.kt`, `Info.plist` | **3.0** (WKWebView) / **2.0 + external** (ASWebAuthenticationSession, needs a Strava app-side redirect-URI change) |
| 10 | **iOS UX polish.** Roboto → San Francisco re-check (`ui/theme/Type.kt`); `Slider`/`Switch` ≥44pt hit targets (`ui/screens/SettingsScreen.kt:315, :364`); iOS dialog button order (5 `AlertDialog` sites); verify `fontFeatureSettings = "tnum"` (`ui/theme/Type.kt:100`); verify `FlowRow` wrapping (3 sites) | several | **1.5** |
| 11 | **iOS app assets.** 1024×1024 AppIcon PNG (flatten `ic_launcher_foreground.xml`'s `<group>` transform), `Info.plist` (`CFBundleDisplayName` = 运动同步, `CFBundleURLTypes` for the OAuth scheme, ATS `NSExceptionDomains` for `www.onelap.cn` + `u.onelap.cn`), `LaunchScreen.storyboard` background `#FFF7F9FC` / `#FF101418`, `NSAllowsArbitraryLoads=false` | Xcode project | **0.75** |
| 12 | **Build, run on simulator, visual regression.** `docs/screenshots/` holds **37 reference screenshots** — a ready-made visual-regression baseline | — | **1.5** |
| | **TOTAL** | | **≈ 11.0** (range **8–14**) |

**UI-only slice excluding work item 9** (i.e. if the OAuth rework is tracked separately):
**≈ 8.0 pd.**

---

## 8. Verdict

# 🟡 MODERATE

**Why not "Easy":**
1. **Module restructuring is unavoidable.** `ui/` is compiled inside an Android-only
   `com.android.application` module (`app/build.gradle.kts:1-7`); the app must be split into a KMP
   module before a single screen can be shared.
2. **`org.jetbrains.androidx.navigation:navigation-compose` is the least mature dependency in the
   stack** (alpha/beta lineage). `ui/AppRoot.kt` is 199 LOC and depends on `NavHost`, `composable`,
   `navArgument`, `NavType`, `currentBackStackEntryAsState`, `hierarchy`, `findStartDestination`.
   With only 9 routes, a hand-rolled nav holder is a cheap fallback.
3. **The Strava OAuth screen has no 1:1 iOS counterpart** and forces a redirect-URI change that
   ripples out of `ui/` into `platform/strava/StravaOAuth.kt` and the Strava app configuration.
4. **Zero safe-area handling** — the app has no `WindowInsets` code at all and depends on
   `enableEdgeToEdge()`; iOS notch/home-indicator behavior must be re-established from scratch.
5. **A `KClass`-vs-`Class` `ViewModelProvider.Factory` API change** and iOS `Dispatchers.Main`
   setup are small but mandatory.

**Why not "Hard":**
- **60.7% of the UI LOC is API-identical** and ports with an import rename.
- **The Android-only surface is tiny and concentrated**: exactly 1 `AndroidView` site
  (`StravaAuthScreen.kt:124`), 1 `LocalContext` site (`SyncScreen.kt:120`), 1
  `rememberLauncherForActivityResult` site (`SyncScreen.kt:123`), 1 permission cluster
  (`SyncScreen.kt:127-134`), 1 `BuildConfig` site (`SyncScreen.kt:114`), 8 `R.drawable` sites,
  1 `java.*` helper file (`Format.kt`, 48 LOC), 1 lifecycle stack (`AppViewModel.kt`),
  1 entry-point file (`MainActivity.kt`).
- **None of the classic CMP blockers are present**: no dynamic color, no pull-to-refresh, no
  DatePicker/TimePicker, no Tooltip, no ModalBottomSheet, no TopAppBar, no DropdownMenu, no
  custom fonts, no `res/font`, no WindowInsets, no clipboard, no biometrics, no camera, no maps,
  no Accompanist, no paging.
- **No `collectAsStateWithLifecycle` and no `viewModel()` composable** → the multiplatform
  lifecycle dependency is confined to `lifecycle-viewmodel` alone.
- **The theme is 100% portable** (`ui/theme/Theme.kt`, `Color.kt`, `Type.kt` = 374 LOC) — only the
  5-vector-drawable `painterResource` indirection in `ui/components/Design.kt` needs touching.

**Recommended sequencing:** land items 1–8 + 11 first (≈ 5.5 pd) to get a *running* iOS build with
all screens except OAuth; then item 9 (≈ 3 pd); then items 10 + 12 (≈ 3 pd) for polish and
visual sign-off.

**Note on scope:** this assessment covers the UI layer only. The non-UI blockers that will gate
the *product* (Room → SQLDelight, DataStore → multiplatform, WorkManager → `BGTaskScheduler`,
Android Keystore → Keychain, `network_security_config` → ATS) are outside this scope but must be
resolved for a shipping iOS app.
