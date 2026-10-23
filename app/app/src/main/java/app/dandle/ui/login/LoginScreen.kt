package app.dandle.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
 * Login screen (functional spec §1.1, UX spec §1): server URL + username + password,
 * inline error banners with distinct copy, cleartext warning dialog, session-expired notice.
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
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Baby Buddy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Track feeds, sleep, and care.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val normalized = state.normalizedServer
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
                                Text("The address your family's Baby Buddy lives at.")
                        }
                    },
                    isError = state.banner == LoginBanner.UNREACHABLE,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.LOGIN_SERVER_URL),
                )
                if (!state.apiKeyMode) {
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
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.LOGIN_PASSWORD),
                    )
                } else {
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

                when (state.banner) {
                    LoginBanner.UNREACHABLE -> ErrorBanner(TestTags.LOGIN_ERROR_BANNER, "Couldn't reach the server — check the URL and your connection.")
                    LoginBanner.BAD_CREDENTIALS -> ErrorBanner(
                        TestTags.LOGIN_ERROR_BANNER,
                        if (state.apiKeyMode) "The server rejected that API key."
                        else "Wrong username or password.",
                    )
                    LoginBanner.NO_CHILDREN -> ErrorBanner(TestTags.LOGIN_ERROR_BANNER, "No children found. Create one in the Baby Buddy web app, then retry.")
                    LoginBanner.HTTP_ERROR -> ErrorBanner(
                        TestTags.LOGIN_ERROR_BANNER,
                        if (state.httpStatus > 0) "The server reported an error (HTTP ${state.httpStatus})."
                        else "The server reported an error.",
                    )
                    LoginBanner.SESSION_EXPIRED -> InfoBanner("Session expired. Sign in again.")
                    null -> Unit
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag(TestTags.LOGIN_SUBMIT),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.heightIn(max = 24.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Signing in…", modifier = Modifier.padding(start = 12.dp))
                    } else if (!state.canSubmit) {
                        // T10 (Elena): a greyed Sign in with no reason reads as "I broke it".
                        // The disabled button says what is missing — no separate hint row, so
                        // the layout never grows and the reason is exactly where she taps.
                        Text(
                            when {
                                state.serverUrl.isBlank() && state.apiKeyMode ->
                                    "Enter the server URL to continue"
                                state.serverUrl.isBlank() ->
                                    "Enter the server URL, username, and password"
                                state.apiKeyMode -> "Paste your API key to continue"
                                else -> "Enter your username and password"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        Text("Sign in")
                    }
                }

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

            if (state.cleartextDialogVisible) {
                AlertDialog(
                    onDismissRequest = viewModel::onCleartextCancel,
                    title = { Text("Unencrypted connection") },
                    // T10 (Elena): neutral-informational — nothing she did wrong; many family
                    // servers run plain http inside their own home network.
                    text = { Text("This server uses http:// — data is not encrypted on the way. Continue?") },
                    confirmButton = {
                        Button(onClick = viewModel::onCleartextContinue) { Text("Continue") }
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
            .background(color = container, shape = MaterialTheme.shapes.small),
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
