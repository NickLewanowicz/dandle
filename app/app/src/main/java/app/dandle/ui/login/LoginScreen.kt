package app.dandle.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dandle.App
import app.dandle.data.auth.LoginFlow
import app.dandle.data.prefs.SessionStore
import app.dandle.ui.TestTags
import app.dandle.ui.theme.BabyBuddyNextTheme
import okio.Path.Companion.toPath

/**
 * Login (brief 04): Dandle brand block + "Where should your data live?" mode picker, then the
 * chosen sub-flow — device-only child setup, the pinned Baby Buddy two-step server flow, or
 * Dandle Cloud (username/password + encryption passphrase). Progressive disclosure throughout:
 * one decision per screen, back always returns to the picker with drafts kept. Cloud
 * create-account (brief 06): generated passphrase with a paste-to-verify proof-of-backup
 * (GENERATE → VERIFY) replaces the old confirm field + "I've saved it" dialog.
 */
@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    viewModel: LoginViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                LoginEvent.SignedIn -> onSignedIn()
            }
        }
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(TestTags.LOGIN),
        ) {
            // Box-scope align keeps the art anchored bottom-center on wide form factors.
            LoginBackgroundArt(modifier = Modifier.align(Alignment.BottomCenter))

            // The sub-flow top bar is PINNED (never scrolls): Back must stay reachable
            // regardless of how far the form is scrolled.
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.step != LoginStep.MODE) {
                    SubFlowTopBar(
                        title = when (state.step) {
                            LoginStep.DEVICE_CHILD -> "Your baby"
                            LoginStep.CLOUD_ENTRY -> "Dandle Cloud"
                            else -> "Baby Buddy server"
                        },
                        onBack = viewModel::onBackToMode,
                    )
                }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .fillMaxWidth()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (state.step) {
                            LoginStep.MODE -> {
                                BrandBlock()
                                ModePicker(state, viewModel)
                            }
                            LoginStep.DEVICE_CHILD -> DeviceChildSetup(state, viewModel)
                            LoginStep.URL, LoginStep.CREDENTIALS -> ServerFlow(state, viewModel)
                            LoginStep.CLOUD_ENTRY -> CloudEntry(state, viewModel)
                        }
                    }
                }
            }

            if (state.cleartextDialogVisible) {
                AlertDialog(
                    onDismissRequest = viewModel::onCleartextCancel,
                    title = { Text("Unencrypted connection") },
                    // T10 (Elena): neutral-informational — nothing she did wrong; many family
                    // servers run plain http inside their own home network.
                    text = { Text("This server uses http:// — data is not encrypted on the way. Continue?") },
                    confirmButton = {
                        // Tagged: with progressive disclosure the step-1 Continue button shares
                        // this text, so automation must be able to select the dialog's own action.
                        Button(
                            onClick = viewModel::onCleartextContinue,
                            modifier = Modifier.testTag(TestTags.LOGIN_CLEARTEXT_CONTINUE),
                        ) { Text("Continue") }
                    },
                    dismissButton = {
                        Button(onClick = viewModel::onCleartextCancel) { Text("Cancel") }
                    },
                    modifier = Modifier.testTag(TestTags.LOGIN_CLEARTEXT_DIALOG),
                )
            }
        }
    }
}

// -- Brand -----------------------------------------------------------------

/** The Dandle brand block (brief 04 §A): fixed-color badge + live-text wordmark + tagline. */
@Composable
private fun BrandBlock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Image(
            painter = painterResource(app.dandle.R.drawable.dandle_login_badge),
            contentDescription = null, // decorative; the wordmark below carries the name
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .testTag(TestTags.LOGIN_LOGO),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Dandle",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Log the day, one tap at a time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Bottom-anchored nursery arcs (brief 04 §A, direction A), runtime-tinted so it harmonizes
 * with dynamic color in both modes. Decorative: 10–12% alpha, additive — the screen is
 * complete without it. The caller anchors the node via Box-scope align, and the Crop's own
 * alignment keeps the arcs at the bottom when a wide form factor crops the art vertically.
 */
@Composable
private fun LoginBackgroundArt(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    Image(
        painter = painterResource(app.dandle.R.drawable.dandle_login_art),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.BottomCenter,
        alpha = if (dark) 0.12f else 0.10f,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.LOGIN_BACKGROUND_ART),
    )
}

// -- Mode picker -------------------------------------------------------------

@Composable
private fun ModePicker(state: LoginUiState, viewModel: LoginViewModel) {
    Text(
        "Where should your data live?",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard(
            tag = TestTags.LOGIN_MODE_DEVICE,
            icon = { ModeIcon(app.dandle.R.drawable.ic_bb_tummy_time, "This device only") },
            title = "This device only",
            subtitle = "Stays on this phone. No account. Not shared.",
            onClick = { viewModel.onModeSelected(LoginMode.DEVICE) },
        )
        ModeCard(
            tag = TestTags.LOGIN_MODE_SERVER,
            icon = {
                // Core icon set: the server you run is a home. Same 24 dp + tint treatment
                // as the bitmap mode icons so all three cards read as one family.
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            },
            title = "Baby Buddy server",
            subtitle = "Syncs with the server you run.",
            onClick = { viewModel.onModeSelected(LoginMode.SERVER) },
        )
        // Dead-card guard (ux review): without an auth backend the card must not exist —
        // never a silent no-op submit.
        if (state.cloudAvailable) {
            ModeCard(
                tag = TestTags.LOGIN_MODE_CLOUD,
                icon = { ModeIcon(app.dandle.R.drawable.ic_bb_cloud_up, "Dandle Cloud") },
                title = "Dandle Cloud",
                subtitle = "Encrypted sync across devices. Starts empty.",
                onClick = { viewModel.onModeSelected(LoginMode.CLOUD) },
            )
        }
    }
    // True in v1: backend switching is confirm-wipe, so the stakes belong on the picker.
    Text(
        "Your data can't be moved between these later — pick the one that matches your family.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ModeCard(
    tag: String,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            // The ROLE belongs on the clickable node itself, not the inner row.
            .semantics { role = Role.Button },
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModeIcon(resId: Int, description: String) {
    Icon(
        painter = painterResource(resId),
        contentDescription = null, // labels carry the meaning; icons are decorative
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(24.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubFlowTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.LOGIN_BACK)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    )
}

// -- Sub-flow: this device only ------------------------------------------------

@Composable
private fun DeviceChildSetup(state: LoginUiState, viewModel: LoginViewModel) {
    var datePickerOpen by remember { mutableStateOf(false) }
    // Same screen, two reasons to be here (brief 04 §A/§B): device-only setup, or the
    // post-cloud-auth child setup for a fresh cloud account (P1 fix QA 2026-10-31).
    Text(
        if (state.mode == LoginMode.CLOUD) "Add your baby to start syncing."
        else "This stays on your phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.childName,
        onValueChange = viewModel::onChildNameChange,
        label = { Text("Name") },
        supportingText = { Text("First name is plenty.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CHILD_NAME),
    )
    val displayDate = state.childBirthDate.takeIf { it.isNotBlank() }?.let { iso ->
        runCatching {
            java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"))
        }.getOrNull()
    }
    OutlinedTextField(
        value = displayDate.orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text("Birth date") },
        supportingText = { Text("Optional — used to show your baby's age.") },
        isError = state.childBirthDate.isNotBlank() && !state.childBirthDateValid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Date, imeAction = ImeAction.Done),
        // Whole-field affordance (ux review): a tap anywhere on the field opens the picker.
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CHILD_BIRTH_DATE)
            .clickable { datePickerOpen = true },
        trailingIcon = {
            TextButton(
                onClick = { datePickerOpen = true },
                modifier = Modifier.testTag(TestTags.LOGIN_CHILD_DATE_PICK),
            ) { Text("Pick") }
        },
    )
    if (state.childBirthDate.isNotBlank() && !state.childBirthDateValid) {
        Text(
            "Birth date can't be in the future.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (datePickerOpen) {
        BirthDatePicker(
            onDismiss = { datePickerOpen = false },
            onPick = { iso ->
                datePickerOpen = false
                viewModel.onChildBirthDateChange(iso)
            },
        )
    }
    SubmitButton(
        tag = TestTags.LOGIN_CHILD_SUBMIT,
        enabled = state.canStartTracking && !state.submitting,
        label = if (state.submitting) "Starting…" else "Start tracking",
        disabledReason = when {
            state.childName.isBlank() -> "Add a name to continue"
            // Blank is now valid (stored as today), so this is only reachable with a typed
            // date that is unparseable or in the future.
            else -> "Pick a birth date that isn't in the future"
        },
        onClick = viewModel::startTracking,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDatePicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val pickerState = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = pickerState.selectedDateMillis ?: return@TextButton
                val local = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                onPick(local.toString())
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

// -- Sub-flow: Baby Buddy server (pinned behavior, brief 04 §B) ------------------

@Composable
private fun ServerFlow(state: LoginUiState, viewModel: LoginViewModel) {
    val normalized = state.normalizedServer
    if (state.step == LoginStep.URL) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server URL") },
            singleLine = true,
            supportingText = {
                when {
                    state.showNormalizationHint && normalized != null ->
                        Text("Will use ${normalized.url}")
                    state.serverUrl.isBlank() ->
                        Text("The address your family's Baby Buddy server lives at.")
                }
            },
            isError = state.banner == LoginBanner.UNREACHABLE,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.LOGIN_SERVER_URL),
        )
        Button(
            onClick = viewModel::onContinueFromUrl,
            enabled = state.canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.LOGIN_CONTINUE),
        ) {
            if (state.verifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("Checking the server…")
            } else {
                Text("Continue")
            }
        }
    }
    if (state.step == LoginStep.CREDENTIALS) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.LOGIN_SERVER_VERIFIED)
                .clickable { viewModel.onBackToUrl() },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp),
            ) {
                Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Server verified", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.verifiedUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Change",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
    if (state.step == LoginStep.CREDENTIALS && !state.apiKeyMode) {
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            isError = state.banner == LoginBanner.BAD_CREDENTIALS,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.LOGIN_USERNAME),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            isError = state.banner == LoginBanner.BAD_CREDENTIALS,
            visualTransformation =
                if (state.passwordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = viewModel::onPasswordVisibilityToggle) {
                    Text(if (state.passwordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.LOGIN_PASSWORD),
        )
    } else if (state.step == LoginStep.CREDENTIALS) {
        // T8 (Priya): self-hosters mint API keys — let them paste one; the server
        // validates it against /api/children/ exactly like the password path.
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            label = { Text("API key") },
            singleLine = true,
            isError = state.banner == LoginBanner.BAD_CREDENTIALS,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.LOGIN_API_KEY),
        )
    }

    LoginBanners(state)

    if (state.step == LoginStep.CREDENTIALS) SubmitButton(
        tag = TestTags.LOGIN_SUBMIT,
        enabled = state.canSubmit,
        label = if (state.submitting) "Signing in…" else "Sign in",
        disabledReason = when {
            state.serverUrl.isBlank() && state.apiKeyMode -> "Enter the server URL to continue"
            state.serverUrl.isBlank() -> "Enter the server URL, username, and password"
            state.apiKeyMode -> "Paste your API key to continue"
            else -> "Enter your username and password"
        },
        onClick = viewModel::submit,
    )

    // T8 (Priya): self-hosters mint API keys for scripts — offer the paste path
    // without making it the default (Elena's first screen stays username+password).
    TextButton(
        onClick = viewModel::onApiKeyModeToggle,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_ADVANCED_TOGGLE),
    ) {
        Text(
            if (state.apiKeyMode) "Use username and password instead"
            else "Advanced: paste an API key",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// -- Sub-flow: Dandle Cloud (brief 04 §B, brief 06) --------------------------------

/**
 * Cloud entry (brief 06): the service-honesty notice sits under the lead line; the tabs split
 * into their own field sets — sign-in stays exactly as shipped (one step), create-account
 * runs the GENERATE → VERIFY passphrase machine. Each tab renders one passphrase field, so
 * the shared `login/cloud_passphrase` tag is never ambiguous to automation.
 */
@Composable
private fun CloudEntry(state: LoginUiState, viewModel: LoginViewModel) {
    Text(
        "Your data is encrypted before it leaves this phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Brief 06 §1, service honesty: muted informational line — the free service may change
    // or end, and the user's data survives that either way (their passphrase is the key).
    Text(
        "Dandle Cloud is a free service and could change or end. Your data stays yours — " +
            "it's encrypted with your passphrase and readable even without us.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_SERVICE_NOTICE),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = state.cloudTab == CloudTab.SIGN_IN,
            onClick = { viewModel.onCloudTabChange(CloudTab.SIGN_IN) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.testTag(TestTags.LOGIN_CLOUD_TAB_SIGNIN),
        ) { Text("Sign in") }
        SegmentedButton(
            selected = state.cloudTab == CloudTab.CREATE_ACCOUNT,
            onClick = { viewModel.onCloudTabChange(CloudTab.CREATE_ACCOUNT) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.testTag(TestTags.LOGIN_CLOUD_TAB_CREATE),
        ) { Text("Create account") }
    }
    if (state.cloudTab == CloudTab.SIGN_IN) {
        CloudSignInFields(state, viewModel)
    } else {
        CloudCreateFields(state, viewModel)
    }
    LoginBanners(state)
}

/** Sign-in tab: one step, unchanged since brief 04 (username + password + passphrase). */
@Composable
private fun CloudSignInFields(state: LoginUiState, viewModel: LoginViewModel) {
    OutlinedTextField(
        value = state.cloudUsername,
        onValueChange = viewModel::onCloudUsernameChange,
        label = { Text("Username") },
        singleLine = true,
        isError = state.banner == LoginBanner.BAD_CREDENTIALS,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_USERNAME),
    )
    OutlinedTextField(
        value = state.cloudPassword,
        onValueChange = viewModel::onCloudPasswordChange,
        label = { Text("Password") },
        singleLine = true,
        isError = state.banner == LoginBanner.BAD_CREDENTIALS,
        visualTransformation =
            if (state.passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = viewModel::onPasswordVisibilityToggle) {
                Text(if (state.passwordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSWORD),
    )
    OutlinedTextField(
        value = state.cloudPassphrase,
        onValueChange = viewModel::onCloudPassphraseChange,
        label = { Text("Encryption passphrase") },
        supportingText = { Text("The one that unlocks your data — different from your password.") },
        singleLine = true,
        isError = state.banner == LoginBanner.WRONG_PASSPHRASE,
        visualTransformation =
            if (state.cloudPassphraseVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = viewModel::onCloudPassphraseVisibilityToggle) {
                Text(if (state.cloudPassphraseVisible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSPHRASE),
    )
    SubmitButton(
        tag = TestTags.LOGIN_CLOUD_SUBMIT,
        enabled = state.canSubmitCloud,
        label = if (state.submitting) "Signing in…" else "Sign in",
        disabledReason = "Enter a username, password, and passphrase",
        onClick = viewModel::submit,
    )
}

/**
 * Create-account tab (brief 06 §2-§4): account section (username, optional contact email,
 * password + confirm side-by-side), then the visually separated encryption-passphrase
 * section running the GENERATE → VERIFY state machine.
 */
@Composable
private fun CloudCreateFields(state: LoginUiState, viewModel: LoginViewModel) {
    OutlinedTextField(
        value = state.cloudUsername,
        onValueChange = viewModel::onCloudUsernameChange,
        label = { Text("Username") },
        singleLine = true,
        isError = state.banner == LoginBanner.USERNAME_TAKEN,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_USERNAME),
    )
    OutlinedTextField(
        value = state.contactEmail,
        onValueChange = viewModel::onContactEmailChange,
        label = { Text("Email (optional)") },
        // Live validation (brief 06 §2): a non-blank value needs an "@" — enough to catch
        // the obvious slip, never a regex that rejects a working address.
        supportingText = {
            Text(
                if (state.contactEmailValid) "Only used to warn you before big service changes. Never marketing."
                else "That doesn't look like an email.",
            )
        },
        isError = !state.contactEmailValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CONTACT_EMAIL),
    )
    OutlinedTextField(
        value = state.cloudPassword,
        onValueChange = viewModel::onCloudPasswordChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation =
            if (state.passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = viewModel::onPasswordVisibilityToggle) {
                Text(if (state.passwordVisible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSWORD),
    )
    // Visually separated section (brief 06 §3): the key that unlocks the data is a different
    // thing from the account password, so it gets its own labeled block.
    HorizontalDivider()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSPHRASE_GENERATE),
    ) {
        Text(
            "Encryption passphrase",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.cloudPassphraseStep) {
            PassphraseStep.GENERATE -> PassphraseGenerateStep(state, viewModel)
            PassphraseStep.VERIFY -> PassphraseVerifyStep(state, viewModel)
        }
        PassphraseWarningRow()
    }
    when (state.cloudPassphraseStep) {
        PassphraseStep.GENERATE -> SubmitButton(
            tag = TestTags.LOGIN_CLOUD_PASSPHRASE_CONTINUE,
            enabled = state.canContinuePassphrase,
            label = "Continue",
            disabledReason = when {
                state.cloudCreatePassphrase.isBlank() -> "Enter a passphrase to continue"
                else -> "Use at least 8 characters"
            },
            onClick = viewModel::onCloudPassphraseContinue,
        )
        PassphraseStep.VERIFY -> {
            SubmitButton(
                tag = TestTags.LOGIN_CLOUD_SUBMIT,
                enabled = state.canSubmitCloud,
                label = if (state.submitting) "Creating account…" else "Create account",
                disabledReason = when {
                    state.cloudPassphraseVerify != state.cloudCreatePassphrase -> "Paste the passphrase to confirm"
                    !state.contactEmailValid -> "Fix the email address to continue"
                                        else -> "Enter a username and password"
                },
                onClick = viewModel::submit,
            )
            TextButton(
                onClick = viewModel::onCloudPassphraseBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.LOGIN_CLOUD_PASSPHRASE_BACK),
            ) { Text("Back") }
        }
    }
}

/**
 * GENERATE (brief 06 §4, step 1 of 2): the passphrase arrives pre-generated and READABLE —
 * it must be seen to be saved. Regenerate replaces it; "Create my own" hands the field to
 * the user (editable, obfuscated, standard Show/Hide).
 */
@Composable
private fun PassphraseGenerateStep(state: LoginUiState, viewModel: LoginViewModel) {
    Text(
        "This unlocks your data. Save it somewhere safe — a password manager is ideal.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.cloudCreatePassphrase,
        onValueChange = viewModel::onCloudCreatePassphraseChange,
        label = { Text("Encryption passphrase") },
        singleLine = true,
        // Generated mode is deliberately readable (nothing to mistype yet — the verify step
        // catches transcription); custom mode behaves like any password field.
        visualTransformation =
            if (state.cloudCreatePassphraseCustom && !state.cloudPassphraseVisible) PasswordVisualTransformation()
            else VisualTransformation.None,
        trailingIcon = {
            if (state.cloudCreatePassphraseCustom) {
                IconButton(onClick = viewModel::onCloudPassphraseVisibilityToggle) {
                    Text(if (state.cloudPassphraseVisible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = viewModel::onCloudPassphraseRegenerate,
                        modifier = Modifier.testTag(TestTags.LOGIN_CLOUD_PASSPHRASE_REGEN),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Generate a new passphrase",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = viewModel::onCloudPassphraseCreateOwn,
                        modifier = Modifier.testTag(TestTags.LOGIN_CLOUD_PASSPHRASE_CUSTOM),
                    ) { Text("Create my own") }
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { viewModel.onCloudPassphraseContinue() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSPHRASE),
    )
}

/**
 * VERIFY (brief 06 §4, step 2 of 2): the MetaMask-style proof-of-backup, softened to
 * paste-from-saved-place. The header keeps the app context visible — seed-phrase-style
 * confirmation screens are a known phishing mimic pattern.
 */
@Composable
private fun PassphraseVerifyStep(state: LoginUiState, viewModel: LoginViewModel) {
    Text(
        "Create your account · step 2 of 2",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    OutlinedTextField(
        value = state.cloudPassphraseVerify,
        onValueChange = viewModel::onCloudPassphraseVerifyChange,
        label = { Text("Encryption passphrase") },
        supportingText = {
            Text(
                if (state.cloudVerifyMismatch) "That doesn't match — paste it again."
                else "Paste your passphrase to confirm you've saved it.",
            )
        },
        singleLine = true,
        isError = state.cloudVerifyMismatch,
        visualTransformation =
            if (state.cloudPassphraseVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = viewModel::onCloudPassphraseVisibilityToggle) {
                Text(if (state.cloudPassphraseVisible) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSPHRASE_VERIFY),
    )
}

/** Information, not an error: muted loss warning, visible in BOTH passphrase steps. */
@Composable
private fun PassphraseWarningRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.LOGIN_CLOUD_PASSPHRASE_WARNING),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "If you lose this passphrase, your data can't be recovered — not even by us.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Shared pieces -----------------------------------------------------------

@Composable
private fun LoginBanners(state: LoginUiState) {
    when (state.banner) {
        LoginBanner.UNREACHABLE -> ErrorBanner(
            TestTags.LOGIN_ERROR_BANNER,
            if (state.step == LoginStep.CLOUD_ENTRY) {
                "Couldn't reach Dandle Cloud — check your connection and try again."
            } else {
                "Couldn't reach the server — check the URL and your connection."
            },
        )
        LoginBanner.BAD_CREDENTIALS -> ErrorBanner(
            TestTags.LOGIN_ERROR_BANNER,
            if (state.apiKeyMode) "The server rejected that API key."
            else "Wrong username or password.",
        )
        LoginBanner.NO_CHILDREN -> ErrorBanner(TestTags.LOGIN_ERROR_BANNER, "No children found. Create one in the Baby Buddy web app, then retry.")
        LoginBanner.HTTP_ERROR -> ErrorBanner(
            TestTags.LOGIN_ERROR_BANNER,
            // Mode-aware like UNREACHABLE (brief 04 §B): the cloud sub-flow never says "server".
            if (state.step == LoginStep.CLOUD_ENTRY) {
                if (state.httpStatus > 0) "Dandle Cloud reported an error (HTTP ${state.httpStatus})."
                else "Dandle Cloud reported an error."
            } else {
                if (state.httpStatus > 0) "The server reported an error (HTTP ${state.httpStatus})."
                else "The server reported an error."
            },
        )
        LoginBanner.SESSION_EXPIRED -> InfoBanner("Session expired. Sign in again.")
        LoginBanner.USERNAME_TAKEN -> ErrorBanner(TestTags.LOGIN_ERROR_BANNER, "That username is taken. Try another.")
        LoginBanner.WRONG_PASSPHRASE -> ErrorBanner(TestTags.LOGIN_ERROR_BANNER, "That passphrase doesn't unlock this account's data.")
        null -> Unit
    }
}

/**
 * Elena's rule (T10): a disabled submit says what is missing — the reason lives INSIDE the
 * button, so the layout never grows and the reason is exactly where she taps.
 */
@Composable
private fun SubmitButton(
    tag: String,
    enabled: Boolean,
    label: String,
    disabledReason: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag),
    ) {
        if (enabled) {
            Text(label)
        } else {
            Text(disabledReason, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ErrorBanner(tag: String, message: String) {
    Banner(
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        message = message,
        tag = tag,
    )
}

@Composable
private fun InfoBanner(message: String) {
    Banner(
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        message = message,
        tag = TestTags.LOGIN_ERROR_BANNER,
    )
}

@Composable
private fun Banner(container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, message: String, tag: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .background(color = container, shape = MaterialTheme.shapes.small)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            message,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

private fun previewSessionStore(): SessionStore =
    SessionStore(
        java.io.File.createTempFile("preview-session", ".preferences_pb").absolutePath.toPath()
    )

@Preview(showBackground = true)
@Composable
private fun LoginScreenPreview() {
    BabyBuddyNextTheme {
        val store = previewSessionStore()
        LoginScreen(
            onSignedIn = {},
            viewModel = LoginViewModel(
                loginFlow = LoginFlow(
                    client = okhttp3.OkHttpClient(),
                    json = kotlinx.serialization.json.Json,
                    sessionStore = store,
                ),
                sessionStore = store,
            ),
        )
    }
}
