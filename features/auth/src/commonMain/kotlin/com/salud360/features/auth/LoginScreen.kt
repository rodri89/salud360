package com.salud360.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.salud360.core.model.auth.Sesion
import com.salud360.core.ui.components.PillButton
import com.salud360.core.ui.theme.Salud360Colors
import org.koin.compose.viewmodel.koinViewModel

/**
 * Pantalla de ingreso. El médico no elige especialidad: se resuelve por su mail.
 * Mantiene la estética original (fondo con degradado teal, botón píldora) sobre una tarjeta centrada.
 */
@Composable
fun LoginScreen(onLogin: (Sesion) -> Unit, vm: LoginViewModel = koinViewModel()) {
    val state by vm.state.collectAsState()
    var verPassword by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(Salud360Colors.BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(28.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(Salud360Colors.BrandGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Text("Salud 360", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Historia clínica digital y turnos", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = state.email,
                    onValueChange = vm::onEmail,
                    label = { Text("Mail") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = if (verPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { verPassword = !verPassword }) {
                            Icon(if (verPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.login(onLogin) }),
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                if (state.licenciaVencida) {
                    Text(
                        "Tu licencia está vencida. Comunicate con el administrador para renovarla.",
                        color = Salud360Colors.Danger, textAlign = TextAlign.Center,
                    )
                }
                if (state.cargando) CircularProgressIndicator()
                else PillButton(text = "Iniciar sesión", onClick = { vm.login(onLogin) }, modifier = Modifier.fillMaxWidth())

                Text(
                    "Si ya ingresaste antes en este dispositivo, podés usar la app sin conexión.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
