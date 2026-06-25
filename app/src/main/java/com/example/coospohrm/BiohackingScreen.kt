package com.example.coospohrm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun BiohackingScreen(data: BiohackingData) {
    var showDialog by remember { mutableStateOf<BiohackingInfo?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🧬 БИОХАКИНГ", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9C27B0))
        Text("Нажмите на показатель для подробностей", fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        // Стресс
        if (data.stressIndex > 0) {
            BiohackingCard(
                title = "Индекс стресса (Баевского)",
                icon = "😰",
                color = Color(0xFFFF5722),
                onClick = { showDialog = BiohackingInfo.STRESS }
            ) {
                Text("${data.stressIndex.roundToInt()}", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text(data.stressLevel, fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Утренние метрики
        if (data.hasMorningData) {
            Text("🌅 УТРЕННИЕ ПОКАЗАТЕЛИ", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE91E63))
            Text("Последний сон: ${data.lastSleepEnd}", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            data.morningReadiness?.let { readiness ->
                BiohackingCard(
                    title = "Готовность к нагрузке (Readiness)",
                    icon = "⚡",
                    color = when {
                        readiness.score > 80 -> Color(0xFF4CAF50)
                        readiness.score > 60 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    },
                    onClick = { showDialog = BiohackingInfo.READINESS }
                ) {
                    Text("${readiness.score}%", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text(readiness.status, fontSize = 14.sp)
                    Text("Рекомендация: ${readiness.recommendation}", fontSize = 13.sp, color = Color.Gray)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (data.morningRMSSD > 0) {
                BiohackingCard(
                    title = "RMSSD (вариабельность)",
                    icon = "💓",
                    color = Color(0xFF2196F3),
                    onClick = { showDialog = BiohackingInfo.RMSSD }
                ) {
                    Text("${data.morningRMSSD.roundToInt()} ms", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Text("Статус: ${data.morningHRV}", fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (data.restingHR > 0) {
                BiohackingCard(
                    title = "Пульс покоя",
                    icon = "❤️",
                    color = Color(0xFF4CAF50),
                    onClick = { showDialog = BiohackingInfo.RESTING_HR }
                ) {
                    Text("${data.restingHR} BPM", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌙", fontSize = 48.sp)
                    Text("Нет утренних данных", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Включите режим сна ночью", fontSize = 14.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // После тренировки
        if (data.hasTrainingData) {
            Text("🏃 ПОСЛЕ ТРЕНИРОВКИ", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
            Text("Последняя тренировка: ${data.lastTrainingEnd}", fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            if (data.vo2max > 0) {
                BiohackingCard(
                    title = "VO₂max",
                    icon = "🫁",
                    color = Color(0xFF009688),
                    onClick = { showDialog = BiohackingInfo.VO2MAX }
                ) {
                    Text("${String.format("%.1f", data.vo2max)} мл/кг/мин", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(when { data.vo2max > 50 -> "Отличный"; data.vo2max > 40 -> "Хороший"; data.vo2max > 30 -> "Средний"; else -> "Низкий" } + " уровень", fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (data.recoveryRate > 0) {
                BiohackingCard(
                    title = "Восстановление пульса (HRR)",
                    icon = "🔄",
                    color = Color(0xFF673AB7),
                    onClick = { showDialog = BiohackingInfo.RECOVERY }
                ) {
                    Text("${data.recoveryRate} BPM/2мин", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(when { data.recoveryRate > 30 -> "Отличное"; data.recoveryRate > 20 -> "Хорошее"; else -> "Медленное" } + " восстановление", fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🏃", fontSize = 48.sp)
                    Text("Нет данных после тренировки", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Завершите тренировку для анализа", fontSize = 14.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Общие показатели
        Text("📊 ОБЩИЕ ПОКАЗАТЕЛИ", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF3F51B5))
        Spacer(Modifier.height(8.dp))

        if (data.heartAge > 0) {
            BiohackingCard(
                title = "Биологический возраст сердца",
                icon = "🫀",
                color = Color(0xFFFF9800),
                onClick = { showDialog = BiohackingInfo.HEART_AGE }
            ) {
                Text("${data.heartAge} лет", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
        }

        if (data.heartHealthScore > 0) {
            BiohackingCard(
                title = "Индекс здоровья сердца",
                icon = "💪",
                color = when {
                    data.heartHealthScore > 80 -> Color(0xFF4CAF50)
                    data.heartHealthScore > 50 -> Color(0xFFFFC107)
                    else -> Color(0xFFF44336)
                },
                onClick = { showDialog = BiohackingInfo.HEART_HEALTH }
            ) {
                Text("${data.heartHealthScore}/100", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // Диалог с пояснениями
    showDialog?.let { info ->
        AlertDialog(
            onDismissRequest = { showDialog = null },
            title = { Text(info.title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(info.description, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("📊 Критерии оценки:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    info.ranges.forEach { (range, label) ->
                        Text("• $range: $label", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("🔬 Как определяется:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(info.method, fontSize = 13.sp, color = Color.Gray)
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = null }) { Text("Понятно") } }
        )
    }
}

data class BiohackingInfo(
    val title: String,
    val description: String,
    val method: String,
    val ranges: List<Pair<String, String>>
) {
    companion object {
        val STRESS = BiohackingInfo(
            title = "Индекс стресса (Баевского)",
            description = "Показывает уровень напряжения регуляторных систем организма. Чем выше индекс, тем больше стрессовая нагрузка на сердце.",
            method = "Рассчитывается из RR-интервалов: индекс = АМо / (2 × ΔX × Мо) × 1000, где Мо — мода (самый частый интервал), АМо — амплитуда моды, ΔX — вариационный размах.",
            ranges = listOf(
                "< 50" to "🟢 Норма (низкий стресс)",
                "50–150" to "🟡 Умеренный стресс",
                "150–300" to "🟠 Высокий стресс",
                "> 300" to "🔴 Очень высокий стресс (перетренированность)"
            )
        )
        val READINESS = BiohackingInfo(
            title = "Готовность к нагрузке (Readiness)",
            description = "Оценка готовности организма к физической нагрузке на основе пульса покоя, вариабельности и качества сна.",
            method = "Сравниваются утренний пульс и RMSSD с baseline (среднее за неделю). Учитывается продолжительность сна. Каждое отклонение снижает score от 100%.",
            ranges = listOf(
                "80–100%" to "🟢 Готов к интенсивной нагрузке (HIIT, силовые)",
                "60–80%" to "🟡 Готов к умеренной нагрузке (кардио)",
                "40–60%" to "🟠 Готов к легкой нагрузке (йога, ходьба)",
                "< 40%" to "🔴 Требуется отдых"
            )
        )
        val RMSSD = BiohackingInfo(
            title = "RMSSD (вариабельность сердечного ритма)",
            description = "Главный показатель активности парасимпатической нервной системы. Отражает способность организма восстанавливаться и адаптироваться к нагрузкам.",
            method = "Квадратный корень из среднего квадратов разностей последовательных RR-интервалов. Чем выше RMSSD, тем лучше восстановление.",
            ranges = listOf(
                "> 50 ms" to "🟢 Отличная вариабельность (спортсмены)",
                "30–50 ms" to "🟡 Нормальная вариабельность",
                "20–30 ms" to "🟠 Пониженная (усталость/стресс)",
                "< 20 ms" to "🔴 Низкая (перетренированность/болезнь)"
            )
        )
        val RESTING_HR = BiohackingInfo(
            title = "Пульс покоя",
            description = "Частота сердечных сокращений в состоянии полного покоя. Один из важнейших показателей здоровья сердечно-сосудистой системы.",
            method = "Измеряется как средний пульс за время сна, исключая периоды пробуждений. Считывается напрямую с датчика H9Z.",
            ranges = listOf(
                "< 50 BPM" to "🟢 Отлично (спортсмены)",
                "50–60 BPM" to "🟢 Хорошо",
                "60–70 BPM" to "🟡 Норма",
                "70–80 BPM" to "🟠 Повышен",
                "> 80 BPM" to "🔴 Высокий (проверьте здоровье)"
            )
        )
        val VO2MAX = BiohackingInfo(
            title = "VO₂max (максимальное потребление кислорода)",
            description = "Максимальный объем кислорода (в мл), который организм способен усвоить за 1 минуту на 1 кг веса. Главный показатель аэробной выносливости.",
            method = "Оценивается по формуле Uth-Sørensen-Overgaard-Pedersen: VO₂max = 15.3 × (HRmax / HRrest). Корректируется по возрасту.",
            ranges = listOf(
                "> 50" to "🟢 Отлично (спортсмены)",
                "40–50" to "🟢 Хорошо (активные)",
                "30–40" to "🟡 Средне",
                "< 30" to "🔴 Низко (рекомендуется кардио)"
            )
        )
        val RECOVERY = BiohackingInfo(
            title = "Восстановление пульса (HRR)",
            description = "Насколько быстро пульс снижается после прекращения нагрузки. Отражает тренированность сердечно-сосудистой системы.",
            method = "Разница между пиковым пульсом на тренировке и пульсом через 2 минуты отдыха. HRR = HRpeak − HRafter2min.",
            ranges = listOf(
                "> 30 BPM" to "🟢 Отличное восстановление",
                "20–30 BPM" to "🟡 Хорошее восстановление",
                "12–20 BPM" to "🟠 Медленное восстановление",
                "< 12 BPM" to "🔴 Плохое (риск сердечных проблем)"
            )
        )
        val HEART_AGE = BiohackingInfo(
            title = "Биологический возраст сердца",
            description = "Оценка возраста сердца на основе пульса покоя, вариабельности (RMSSD) и способности к восстановлению (HRR).",
            method = "Сравниваются ваши показатели со среднестатистическими для вашего хронологического возраста. Каждый 'хороший' показатель снижает биологический возраст, каждый 'плохой' — повышает.",
            ranges = listOf(
                "Меньше паспортного" to "🟢 Сердце моложе вас!",
                "Равен паспортному" to "🟡 Норма",
                "Больше паспортного" to "🔴 Сердце старше — займитесь кардио"
            )
        )
        val HEART_HEALTH = BiohackingInfo(
            title = "Индекс здоровья сердца",
            description = "Комплексная оценка здоровья сердца по шкале 0–100 на основе пульса покоя, вариабельности и восстановления.",
            method = "Score = 100 − штрафы за повышенный пульс покоя (−25 за >80 BPM), низкий RMSSD (−25 за <20 ms), медленное восстановление (−20 за <12 BPM). Бонусы за отличные показатели.",
            ranges = listOf(
                "80–100" to "🟢 Отличное здоровье сердца",
                "60–80" to "🟡 Хорошее",
                "40–60" to "🟠 Среднее (рекомендуется кардио)",
                "< 40" to "🔴 Низкое (обратитесь к врачу)"
            )
        )
    }
}

@Composable
private fun BiohackingCard(
    title: String,
    icon: String,
    color: Color,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 24.sp)
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}