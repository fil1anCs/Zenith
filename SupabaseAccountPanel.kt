package com.zenith.player.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.zenith.player.sync.ZenithSyncWorker

@Composable
fun SupabaseAccountPanel() {
    val context = LocalContext.current
    val repository = remember { SupabaseAuthRepository(context) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(repository.currentSession()) }
    var profileName by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(session?.userId) {
        profileName = if (session != null) repository.profileName() else null
    }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Облачный аккаунт", style = MaterialTheme.typography.titleLarge)
            if (!repository.configured) {
                Text("Supabase пока не подключён. Сначала создай проект Supabase, затем заполни local.properties по образцу local.properties.example и пересобери приложение.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (session != null) {
                Text("Выполнен вход", style = MaterialTheme.typography.titleMedium)
                Text(profileName?.takeIf { it.isNotBlank() } ?: session?.email.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Локальная музыка остаётся на устройстве. Аккаунт используется для облачных данных и синхронизации.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; message = ""
                        try { repository.signOut(); session = null; message = "Вы вышли из аккаунта." }
                        catch (e: Exception) { message = e.message ?: "Не удалось выйти." }
                        finally { busy = false }
                    }
                }) { Text("Выйти") }
            } else {
                if (isRegister) {
                    OutlinedTextField(displayName, { displayName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Имя профиля") }, singleLine = true)
                }
                OutlinedTextField(email, { email = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Электронная почта") }, singleLine = true)
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && email.contains('@') && password.length >= 6, onClick = {
                        scope.launch {
                            busy = true; message = ""
                            try {
                                session = if (isRegister) repository.signUp(email, password, displayName) else repository.signIn(email, password)
                                ZenithSyncWorker.enqueue(context)
                                message = if (isRegister) "Аккаунт создан и подключён." else "Вход выполнен."
                            } catch (e: Exception) { message = e.message ?: "Не удалось выполнить запрос." }
                            finally { busy = false }
                        }
                    }) { if (busy) CircularProgressIndicator() else Text(if (isRegister) "Создать аккаунт" else "Войти") }
                    OutlinedButton(enabled = !busy, onClick = { isRegister = !isRegister; message = "" }) {
                        Text(if (isRegister) "Уже есть аккаунт" else "Регистрация")
                    }
                }
                Text("Пароль передаётся напрямую в Supabase Auth по HTTPS. Токены сессии хранятся зашифрованно через Android Keystore.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
