package com.stai.pleos_red_light_voca_app.presentation.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stai.pleos_red_light_voca_app.core.entity.SignalBlock
import com.stai.pleos_red_light_voca_app.core.entity.SignalFailure
import com.stai.pleos_red_light_voca_app.core.entity.VehicleSignal
import com.stai.pleos_red_light_voca_app.domain.source.SourceMode
import dagger.hilt.android.EntryPointAccessors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sim = remember(context) {
        EntryPointAccessors.fromApplication(context, VehicleSimulatorEntryPoint::class.java).vehicleSimulator()
    }
    val mode by sim.mode.collectAsState()
    val speed by sim.speedKmh.collectAsState()
    val gearLabel by sim.gearLabel.collectAsState()
    var speedInput by remember { mutableStateOf("") }
    val isFake = mode == SourceMode.FAKE
    val gap = Arrangement.spacedBy(8.dp)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("디버그 패널 (debug 빌드 전용)", style = MaterialTheme.typography.titleMedium)

            Text("차량 신호 소스: $mode")
            FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                SourceMode.entries.forEach { m ->
                    if (m == mode) Button(onClick = { sim.setMode(m) }) { Text(m.name) }
                    else OutlinedButton(onClick = { sim.setMode(m) }) { Text(m.name) }
                }
            }

            if (isFake) {
                Text("가짜 속도 %.1f km/h, 기어 %s".format(speed, gearLabel))
                Text("속도")
                FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                    listOf(0f, 0.3f, 0.8f, 1.5f, 5f, 10f, 30f).forEach { v ->
                        OutlinedButton(onClick = { sim.setSpeed(v) }) { Text("$v") }
                    }
                }
                FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                    OutlinedTextField(
                        value = speedInput,
                        onValueChange = { speedInput = it },
                        label = { Text("속도 km/h") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Button(
                        onClick = { speedInput.trim().toFloatOrNull()?.let { sim.setSpeed(it) } },
                        enabled = speedInput.trim().toFloatOrNull() != null,
                    ) { Text("적용") }
                }
                Text("기어")
                FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                    listOf("DRIVE", "PARK", "REVERSE", "NEUTRAL", "UNKNOWN", "GEAR_1").forEach { g ->
                        OutlinedButton(onClick = { sim.setGear(g) }) { Text(g) }
                    }
                }
            }

            Text("오류 주입 (FAKE 모드 전용)")
            FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                OutlinedButton(
                    enabled = isFake,
                    onClick = { sim.fail(VehicleSignal.SPEED, SignalFailure.UNAVAILABLE) },
                ) { Text("속도 UNAVAILABLE") }
                OutlinedButton(
                    enabled = isFake,
                    onClick = { sim.fail(VehicleSignal.GEAR, SignalFailure.ERROR) },
                ) { Text("기어 ERROR") }
                OutlinedButton(
                    enabled = isFake,
                    onClick = { sim.fail(VehicleSignal.SPEED, SignalFailure.DISCONNECTED) },
                ) { Text("속도 DISCONNECTED") }
                OutlinedButton(
                    enabled = isFake,
                    onClick = { sim.block(VehicleSignal.SPEED, SignalBlock.PERMISSION_DENIED) },
                ) { Text("속도 권한 거부") }
            }

            Text("미디어 명령")
            FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
                listOf("Play" to "play", "Pause" to "pause", "Stop" to "stop", "PlayPause" to "playpause")
                    .forEach { (label, cmd) ->
                        Button(onClick = { DebugMedia.send(context, cmd) }) { Text(label) }
                    }
            }
        }
    }
}
