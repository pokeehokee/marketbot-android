package com.marketbot.core.contract

import com.marketbot.core.kalshi.KALSHI_DEMO_BASE
import com.marketbot.core.kalshi.KalshiClient
import com.marketbot.core.net.KalshiSigner
import com.marketbot.core.polymarket.PolymarketClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The daily API wording check (`./gradlew :core:contractTest`, scheduled by
 * .github/workflows/api-contract.yml). Talks to the live venues, so the normal
 * build never runs it. Fails on any FAIL finding; WARN findings are written to the
 * report, which the workflow turns into an issue.
 */
class VenueLiveContractTest {
    @Test fun `the venues still answer in the words the app relies on`() = runBlocking {
        val now = Instant.now()
        val findings = mutableListOf<ApiContract.Finding>()
        findings += ApiContract.run(KalshiClient(null), PolymarketClient(null), now).findings
        findings += specFindings()
        findings += demoFindings()
        val report = ApiContract.Report(now.toString(), findings)
        val status = when { report.failures.isNotEmpty() -> "FAIL"; report.warnings.isNotEmpty() -> "WARN"; else -> "PASS" }
        val md = buildString {
            appendLine("STATUS: $status")
            appendLine()
            appendLine("API wording check, $now: ${report.summary}")
            appendLine()
            appendLine("| verdict | area | check | detail | affects |")
            appendLine("| --- | --- | --- | --- | --- |")
            findings.sortedBy { it.verdict.ordinal.let { o -> listOf(2, 1, 3, 0)[o] } }.forEach {
                appendLine("| ${it.verdict} | ${it.area} | ${it.check} | ${it.detail.replace("|", "/")} | ${it.affects} |")
            }
        }
        System.getProperty("contract.report")?.let { File(it).apply { parentFile.mkdirs(); writeText(md) } }
        println(md)
        assertTrue(report.failures.isEmpty(), report.failures.joinToString("\n") { it.line })
    }

    private fun specFindings(): List<ApiContract.Finding> {
        val text = runCatching {
            OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("https://docs.kalshi.com/openapi.yaml").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}") else r.body!!.string()
                }
        }
        return text.fold({ t ->
            @Suppress("UNCHECKED_CAST")
            val spec = org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.LoaderOptions().apply { codePointLimit = 50_000_000 }).load<Any>(t) as Map<String, Any?>
            KalshiSpecCheck.check(spec)
        }, { e -> listOf(ApiContract.Finding("Kalshi spec", "download", ApiContract.Verdict.SKIP, "docs.kalshi.com/openapi.yaml: ${e.message}", "the spec check")) })
    }

    private suspend fun demoFindings(): List<ApiContract.Finding> {
        val id = System.getenv("KALSHI_DEMO_KEY_ID")?.takeIf { it.isNotBlank() }
        val pem = System.getenv("KALSHI_DEMO_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
        val k = KalshiClient(if (id != null && pem != null) KalshiSigner(id, pem) else null, KALSHI_DEMO_BASE)
        return ApiContract.kalshiDemoWording(k)
    }
}
