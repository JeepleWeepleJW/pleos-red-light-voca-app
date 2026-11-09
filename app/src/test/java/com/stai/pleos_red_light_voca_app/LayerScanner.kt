package com.stai.pleos_red_light_voca_app

import java.io.File

/**
 * [LayerDependencyTest]가 쓰는 소스 스캐너와 규칙. 파일뿐 아니라 문자열 입력에도 쓸 수 있게
 * [LayerScanner.source]로 [ScannedSource]를 만들고, 규칙 함수는 소스 목록을 받아 위반 문구 목록을 돌려준다.
 *
 * 과한 파서는 쓰지 않는다: 주석·문자열을 지우는 문자 스캐너 + 정규식.
 *
 * 스캔 범위: `app/src/` 아래 이름이 `test`·`androidTest`로 시작하지 않는 모든 소스셋(main·debug·release, 이후 flavor 포함)의
 * `java/`·`kotlin/` 아래 모든 `.kt`·`.java`. 패키지 디렉터리(`com/stai/pleos_red_light_voca_app`) 밖 파일은 계층 `outside`로
 * 두고 [filesOutsidePackageDir]가 위반으로 잡는다(다른 계층 규칙은 그 파일을 보지 않는다).
 *
 * 알려진 한계:
 * - 선언·참조 판정은 정규식이다. 최상위 선언은 줄 맨 앞(들여쓰기 없음)에 있어야 잡힌다. 타입 추론으로 생기는 의존
 *   (예: UseCase가 돌려준 값의 멤버를 이름 없이 쓰기)은 보지 않는다. 같은 단순 이름의 다른 타입을 구분하지 못한다.
 * - 와일드카드 import 뒤 단순 이름은 스캔한 소스에서 그 패키지에 선언된 최상위 이름(+명시 금지 이름)으로만 판정한다.
 * - typealias 우회 검사는 domain의 `typealias X = …` 우변 식별자를 import·같은 패키지로 풀어 본다. 별칭의 별칭(여러 단계)은 따라가지 않는다.
 * - UseCase 생성자 속성 검사는 이름이 `…UseCase`인 클래스의 주 생성자만 본다(본문 속성·보조 생성자는 보지 않음).
 * - 변형 전용 타입 검사는 main만 본다. 변형 집합은 스캔한 main 밖 소스셋 + debug·release이며, flavor 조합별(예: pleosDebug만 있는 이름)
 *   가시성은 따지지 않고 "모든 변형 소스셋에 있음"만 허용한다. debug 허용(`VehicleSimulator`·`SourceMode`)은 소스셋 이름이 `debug`일 때만.
 * - 줄 넘김 정규화는 점 앞뒤의 줄바꿈만 잇는다(`import a⏎.b`, `a.⏎b`). 그 밖의 줄 넘김 문장은 보지 않는다.
 */
internal data class ScannedSource(
    /** 소스셋 이름(main·debug·release…) */
    val set: String,
    /** 표시용 경로(예: `main/core/common/Logger.kt`, 패키지 디렉터리 밖이면 `main/java/Leak.kt`) */
    val path: String,
    /** 패키지 루트 기준 상대 경로(예: `core/common/Logger.kt`). 패키지 디렉터리 밖이면 java·kotlin 루트 기준 */
    val rel: String,
    /** 디렉터리로 정한 계층(core·domain·data·presentation·di), 그 밖이면 root, 패키지 디렉터리 밖이면 outside */
    val layer: String,
    /** 선언한 package(없으면 null) */
    val packageName: String?,
    /** 주석·문자열을 지우고 백틱을 벗긴 본문 */
    val stripped: String,
    /** import(별칭 `as`는 뗀 이름, 와일드카드는 `.*` 포함) */
    val imports: List<String>,
    /** import·package 문을 뺀 본문에서 찾은 완전한 이름 참조(프로젝트 패키지) */
    val projectRefs: List<String>,
    /** import·package 문을 뺀 본문에서 찾은 플랫폼 접두어 참조 */
    val platformRefs: List<String>,
)

internal object LayerScanner {

    const val ROOT = "com.stai.pleos_red_light_voca_app"
    val LAYERS = listOf("core", "domain", "data", "presentation", "di")

    /** 루트 패키지의 생성 클래스. 어느 계층에서 참조해도 위반이 아니다. */
    private val GENERATED = setOf("R", "BuildConfig")

    val PLATFORM_PREFIXES = listOf(
        "android.", "androidx.", "ai.pleos.", "timber.", "dagger.", "javax.inject.", "jakarta.inject.", "com.google.", "org.json.", "dalvik.",
        "kotlinx.coroutines.android.",
    )

    private val projectRefRegex = Regex("""(?<![\w.])${Regex.escape(ROOT)}(?:\.\w+)+""")
    private val platformRefRegex = Regex(
        """(?<![\w.])(?:""" +
            PLATFORM_PREFIXES.joinToString("|") { Regex.escape(it.removeSuffix(".")) } +
            """)\.\w[\w.]*""",
    )

    // ---------------------------------------------------------------- 파일 읽기

    /** 스캔하는 소스셋: `src/` 아래 이름이 test·androidTest로 시작하지 않는 디렉터리 */
    fun isScannedSourceSet(name: String) = !name.startsWith("test") && !name.startsWith("androidTest")

    /** 모듈의 `src` 디렉터리(Gradle 테스트 작업 디렉터리는 모듈 루트, IDE는 저장소 루트일 수 있다) */
    fun defaultBase(): File = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }

    /** [base] 아래 스캔 대상 소스셋의 `java/`·`kotlin/` 아래 .kt·.java 전부 */
    fun scanProject(base: File = defaultBase()): List<ScannedSource> {
        val pkgDir = ROOT.replace('.', '/') + "/"
        val sets = base.listFiles { f -> f.isDirectory && isScannedSourceSet(f.name) }.orEmpty().sortedBy { it.name }
        return sets.flatMap { setDir ->
            listOf("java", "kotlin").flatMap { rootName ->
                val root = File(setDir, rootName)
                if (!root.isDirectory) {
                    emptyList()
                } else {
                    root.walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                        .map { f ->
                            val fromRoot = f.relativeTo(root).invariantSeparatorsPath
                            if (fromRoot.startsWith(pkgDir)) {
                                source(setDir.name, fromRoot.removePrefix(pkgDir), f.readText())
                            } else {
                                source(setDir.name, fromRoot, f.readText(), inPackageDir = false, display = "${setDir.name}/$rootName/$fromRoot")
                            }
                        }
                        .toList()
                }
            }
        }
    }

    /**
     * 문자열 입력으로 소스를 만든다. [rel]은 패키지 루트 기준 경로(확장자로 Kotlin/Java를 정한다).
     * [inPackageDir]가 false면 패키지 디렉터리 밖 파일(계층 outside, [rel]은 java·kotlin 루트 기준).
     */
    fun source(set: String, rel: String, text: String, inPackageDir: Boolean = true, display: String? = null): ScannedSource {
        val first = rel.substringBefore('/', missingDelimiterValue = "")
        val stripped = joinDottedLines(strip(text, kotlin = !rel.endsWith(".java")))
        val (pkg, imports, body) = splitHeader(stripped)
        return ScannedSource(
            set = set,
            path = display ?: "$set/$rel",
            rel = rel,
            layer = when {
                !inPackageDir -> "outside"
                first in LAYERS -> first
                else -> "root"
            },
            packageName = pkg,
            stripped = stripped,
            imports = imports,
            projectRefs = projectRefRegex.findAll(body).map { it.value }.toList(),
            platformRefs = platformRefRegex.findAll(body).map { it.value }.toList(),
        )
    }

    // ---------------------------------------------------------------- 전처리

    private sealed interface Mode {
        /** 코드. 문자열 템플릿 `${…}` 안이면 [template]이 true, [depth]는 그 안의 `{` 깊이. */
        class Code(val template: Boolean, var depth: Int = 0) : Mode
        data object Str : Mode
        data object Raw : Mode
    }

    /**
     * 주석(`//`, 중첩 `/* */`, KDoc)과 문자열·문자 리터럴을 지운다. 줄바꿈은 남긴다(줄 수 유지).
     * 리터럴 자리에는 공백 하나를 둔다. Kotlin 문자열 템플릿 `${…}` 안은 코드로 남긴다.
     * 백틱은 지워 식별자를 정규화한다.
     */
    fun strip(text: String, kotlin: Boolean = true): String {
        val out = StringBuilder(text.length)
        val stack = ArrayDeque<Mode>().apply { addLast(Mode.Code(template = false)) }
        var i = 0
        val n = text.length
        fun at(s: String) = text.startsWith(s, i)
        while (i < n) {
            val c = text[i]
            when (val mode = stack.last()) {
                is Mode.Code -> when {
                    at("//") -> {
                        while (i < n && text[i] != '\n') i++
                    }
                    at("/*") -> {
                        var depth = 0
                        while (i < n) {
                            if (text.startsWith("/*", i) && (kotlin || depth == 0)) { depth++; i += 2; continue }
                            if (text.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) break; continue }
                            if (text[i] == '\n') out.append('\n')
                            i++
                        }
                        out.append(' ')
                    }
                    at("\"\"\"") -> { stack.addLast(Mode.Raw); out.append(' '); i += 3 }
                    c == '"' -> { stack.addLast(Mode.Str); out.append(' '); i++ }
                    c == '\'' -> {
                        // 문자 리터럴: 'a', '\n', 'A', '"'
                        i++
                        while (i < n && text[i] != '\'' && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
                        i++
                        out.append(' ')
                    }
                    c == '`' -> i++
                    c == '{' -> { mode.depth++; out.append(c); i++ }
                    c == '}' && mode.template && mode.depth == 0 -> { stack.removeLast(); out.append(' '); i++ }
                    c == '}' -> { mode.depth--; out.append(c); i++ }
                    else -> { out.append(c); i++ }
                }
                Mode.Str -> when {
                    c == '\\' -> i += 2
                    c == '"' -> { stack.removeLast(); i++ }
                    c == '\n' -> { stack.removeLast(); out.append('\n'); i++ } // 닫히지 않은 문자열: 줄에서 끊는다
                    kotlin && at("\${") -> { stack.addLast(Mode.Code(template = true)); out.append(' '); i += 2 }
                    else -> i++
                }
                Mode.Raw -> when {
                    at("\"\"\"") -> {
                        // 닫는 따옴표는 연속한 따옴표의 마지막 3개
                        while (i < n && text[i] == '"') i++
                        stack.removeLast()
                    }
                    kotlin && at("\${") -> { stack.addLast(Mode.Code(template = true)); out.append(' '); i += 2 }
                    c == '\n' -> { out.append('\n'); i++ }
                    else -> i++
                }
            }
        }
        return out.toString()
    }

    private val breakBeforeDot = Regex("""[ \t]*\n\s*\.(?![.])""")
    private val breakAfterDot = Regex("""(?<![.?])\.[ \t]*\n\s*""")

    /**
     * 점 앞뒤의 줄바꿈을 지워 줄을 넘긴 이름을 잇는다(`import com.x⏎    .data.Y` → `import com.x.data.Y`).
     * 주석·문자열을 지운 뒤에 부르므로 리터럴 안의 점은 남아 있지 않다. 이 때문에 줄 수는 줄 수 있다.
     */
    fun joinDottedLines(s: String): String = s.replace(breakBeforeDot, ".").replace(breakAfterDot, ".")

    /** 공백을 접고 점 앞뒤 공백을 지운다(`android . util` → `android.util`). */
    private fun normalizeName(s: String) = s.trim().replace(Regex("""\s*\.\s*"""), ".").replace(Regex("""\s+"""), " ")

    /**
     * package·import 문을 뽑고, 그 문장을 뺀 본문을 돌려준다. 한 줄에 `;`로 여러 문장이 있어도 나눈다.
     * import는 `as` 별칭을 떼고, Java `import static`의 `static`을 뗀다.
     */
    private fun splitHeader(stripped: String): Triple<String?, List<String>, String> {
        var pkg: String? = null
        val imports = mutableListOf<String>()
        val body = StringBuilder()
        for (line in stripped.split('\n')) {
            val kept = line.split(';').filter { seg ->
                val t = seg.trim()
                when {
                    t.startsWith("package ") || t.startsWith("package\t") -> {
                        pkg = normalizeName(t.removePrefix("package"))
                        false
                    }
                    t.startsWith("import ") || t.startsWith("import\t") -> {
                        val name = normalizeName(t.removePrefix("import")).removePrefix("static ")
                        imports += name.split(" as ").first().trim()
                        false
                    }
                    else -> true
                }
            }
            body.append(kept.joinToString(";")).append('\n')
        }
        return Triple(pkg, imports, body.toString())
    }

    // ---------------------------------------------------------------- 참조 분류

    /** 프로젝트 참조의 계층. 프로젝트 밖이거나 생성 클래스(R·BuildConfig)면 null, 계층 밖이면 root. */
    fun layerOf(ref: String): String? {
        if (!ref.startsWith("$ROOT.")) return null
        val first = ref.removePrefix("$ROOT.").substringBefore('.')
        return when (first) {
            in LAYERS -> first
            in GENERATED -> null
            else -> "root"
        }
    }

    /** (설명, 이름) 쌍: import와 본문 FQ 참조 모두 */
    fun projectReferences(s: ScannedSource): List<Pair<String, String>> =
        s.imports.map { "import $it" to it } + s.projectRefs.map { "ref $it" to it }

    fun platformReferences(s: ScannedSource): List<Pair<String, String>> =
        s.imports.filter { i -> PLATFORM_PREFIXES.any { i.startsWith(it) } }.map { "import $it" to it } +
            s.platformRefs.map { "ref $it" to it }

    private fun List<ScannedSource>.layerViolations(check: (ScannedSource, String) -> Boolean): List<String> =
        flatMap { s -> projectReferences(s).filter { (_, name) -> layerOf(name)?.let { check(s, it) } == true }.map { "${s.path}: ${it.first}" } }

    private fun List<ScannedSource>.platformViolations(check: (ScannedSource, String) -> Boolean): List<String> =
        flatMap { s -> platformReferences(s).filter { (_, name) -> check(s, name) }.map { "${s.path}: ${it.first}" } }

    // ---------------------------------------------------------------- 의존 규칙

    fun coreDependsOnOtherLayer(src: List<ScannedSource>) =
        src.layerViolations { s, l -> s.layer == "core" && l != "core" }

    fun domainDependsOnNonCore(src: List<ScannedSource>) =
        src.layerViolations { s, l -> s.layer == "domain" && l != "core" && l != "domain" }

    fun dataDependsOnPresentationOrDi(src: List<ScannedSource>) =
        src.layerViolations { s, l -> s.layer == "data" && l in setOf("presentation", "di", "root") }

    fun presentationDependsOnDataOrDi(src: List<ScannedSource>) =
        src.layerViolations { s, l -> s.layer == "presentation" && l in setOf("data", "di", "root") }

    /** 루트(VocaApplication)는 di·domain·core와 플랫폼만 본다(data·presentation 금지, Timber는 [timberOutsideAllowedLayers]). */
    fun rootDependsOnDataOrPresentation(src: List<ScannedSource>) =
        src.layerViolations { s, l -> s.layer == "root" && l in setOf("data", "presentation") }

    fun diUsedOutsideDi(src: List<ScannedSource>) =
        src.layerViolations { s, l -> s.layer !in setOf("di", "root") && l == "di" }

    fun coreOrDomainUsesPlatform(src: List<ScannedSource>) =
        src.platformViolations { s, _ -> s.layer in setOf("core", "domain") }

    fun timberOutsideAllowedLayers(src: List<ScannedSource>) =
        src.platformViolations { s, name -> s.layer !in setOf("data", "presentation", "di") && name.startsWith("timber.") }

    /** package 선언이 디렉터리와 같아야 한다(다른 계층 패키지를 선언해 import 없이 쓰는 우회 방지). */
    fun packageMismatch(src: List<ScannedSource>): List<String> = src.mapNotNull { s ->
        val dir = s.rel.substringBeforeLast('/', missingDelimiterValue = "")
        val expected = if (dir.isEmpty()) ROOT else "$ROOT." + dir.replace('/', '.')
        if (s.packageName == expected) null else "${s.path}: package ${s.packageName} (기대 $expected)"
    }

    private val forbiddenForPresentation = listOf(
        "$ROOT.domain.session.AppSession",
        "$ROOT.domain.learning.LearningController",
    )
    private val forbiddenSimpleNames = forbiddenForPresentation.map { it.substringAfterLast('.') }

    /** presentation은 AppSession·LearningController를 보지 않는다(import·FQ·와일드카드 import 뒤 단순 이름 사용). */
    fun presentationUsesSessionInternals(src: List<ScannedSource>): List<String> =
        src.filter { it.layer == "presentation" }.flatMap { s ->
            val byName = projectReferences(s).filter { (_, name) ->
                forbiddenForPresentation.any { name == it || name.startsWith("$it.") }
            }.map { "${s.path}: ${it.first}" }
            val bySimple = forbiddenSimpleNames.filter { Regex("""\b$it\b""").containsMatchIn(stripHeader(s)) }
                .map { "${s.path}: 이름 $it" }
            byName + bySimple
        }

    private fun stripHeader(s: ScannedSource): String =
        s.stripped.lines().filterNot { it.trim().startsWith("import ") || it.trim().startsWith("package ") }.joinToString("\n")

    private fun word(name: String) = Regex("""(?<![\w])${Regex.escape(name)}(?![\w])""")

    // ---------------------------------------------------------------- 파일 범위·플랫폼

    /** 패키지 디렉터리(`com/stai/pleos_red_light_voca_app`) 밖 소스 파일 */
    fun filesOutsidePackageDir(src: List<ScannedSource>): List<String> =
        src.filter { it.layer == "outside" }.map { "${it.path}: 패키지 디렉터리 밖" }

    private val mainDispatcherRef = Regex("""\bDispatchers\s*\.\s*Main\b""")

    /** core·domain은 `Dispatchers.Main`(`.immediate` 포함)을 쓰지 않는다(import·본문). 메인 디스패처는 플랫폼 쪽에서 주입한다. */
    fun coreOrDomainUsesMainDispatcher(src: List<ScannedSource>): List<String> =
        src.filter { it.layer in setOf("core", "domain") }.flatMap { s ->
            s.imports.filter { it == "kotlinx.coroutines.Dispatchers.Main" || it.startsWith("kotlinx.coroutines.Dispatchers.Main.") }
                .map { "${s.path}: import $it" } +
                mainDispatcherRef.findAll(stripHeader(s)).map { "${s.path}: ${it.value}" }.toList()
        }

    // ---------------------------------------------------------------- 최상위 선언

    private const val ANNOTATIONS = """(?:@[\w.:]+(?:\([^)\n]*\))?\s+)*"""
    private val topLevelTypeDecl = Regex("""(?m)^$ANNOTATIONS($MODS)(?:class|interface|object|typealias)\s+(\w+)""")
    private val topLevelFunDecl = Regex(
        """(?m)^$ANNOTATIONS((?:(?:public|internal|private|inline|suspend|operator|infix|tailrec|external|expect|actual)\s+)*)""" +
            """fun\s+(?:<[^>\n]*>\s*)?(?:[\w.<>?, ]+?\.)?(\w+)\s*\(""",
    )

    /** 파일의 최상위 선언 이름(class·interface·object·enum·typealias·fun, private 제외) */
    fun topLevelNames(s: ScannedSource): Set<String> =
        (topLevelTypeDecl.findAll(s.stripped) + topLevelFunDecl.findAll(s.stripped))
            .filterNot { Regex("""\bprivate\b""").containsMatchIn(it.groupValues[1]) }
            .map { it.groupValues[2] }
            .toSet()

    /** 패키지 → 그 패키지의 최상위 선언 이름 */
    private fun declaredByPackage(src: List<ScannedSource>): Map<String, Set<String>> =
        src.filter { it.packageName != null }.groupBy { it.packageName!! }
            .mapValues { (_, l) -> l.flatMap { topLevelNames(it) }.toSet() }

    // ---------------------------------------------------------------- presentation → domain 허용 목록

    private fun d(name: String) = "$ROOT.domain.$name"

    /**
     * presentation이 참조할 수 있는 domain: UseCase 패키지 전체 + UseCase 입출력에 쓰이는 값 타입(과 그 중첩 타입).
     * 실제 코드(2026-11-08)에서 presentation(main·debug·release)이 쓰는 domain 타입:
     * - `domain.usecase.*`: AttachPlayer·CheckAudio·DetachPlayer·GetLearningState·HandlePlayCommand·ObserveLearningState·
     *   ObserveSessionState·ObserveAudioStatus·PrepareChapterAudio·ReportPauseCommand·ReportPlayback·ReportStopCommand·
     *   RestartVehicle·SelectContent·StartLearning·StopLearning·ValidatePlayTicket (…UseCase)
     * - `domain.gate.GatePhase`(enum, LearningState 필드)
     * - `domain.learning`: LearningState(data class), PlaybackEnd(enum), PlaybackPhase(enum, LearningState 필드), PauseReason(PlaybackEnd의 typealias),
     *   PlayTicket(data class), PlaybackReport(sealed 결과), PlayerControl(AttachPlayer·DetachPlayer 입력 포트, fun interface)
     * - `domain.playback`: PlaySource(enum), PlayCommandResult(sealed 결과)
     * - `domain.session`: SessionState·AudioStatus·StartOutcome(sealed 결과)
     * - `domain.speech.ChapterAudioResult`(sealed 결과)
     * - debug 소스셋만: `domain.source.VehicleSimulator`·`SourceMode`(debug 패널·리시버)
     */
    val PRESENTATION_DOMAIN_PACKAGES = listOf(d("usecase"))
    val PRESENTATION_DOMAIN_TYPES = listOf(
        d("gate.GatePhase"),
        d("learning.LearningState"), d("learning.PlaybackEnd"), d("learning.PauseReason"),
        d("learning.PlayTicket"), d("learning.PlaybackReport"), d("learning.PlayerControl"), d("learning.PlaybackPhase"),
        d("playback.PlaySource"), d("playback.PlayCommandResult"),
        d("session.SessionState"), d("session.AudioStatus"), d("session.StartOutcome"),
        d("speech.ChapterAudioResult"),
    )
    val PRESENTATION_DOMAIN_TYPES_DEBUG = listOf(d("source.VehicleSimulator"), d("source.SourceMode"))

    /** 허용 목록과 별개로 명시 금지(허용 목록에 잘못 들어가도 금지). typealias 우회 검사도 이 목록과 아래 패키지를 쓴다. */
    val FORBIDDEN_DOMAIN_TYPES = listOf(
        d("session.AppSession"), d("learning.LearningController"), d("gate.StopGate"),
        d("session.PlayerControlRelay"), d("speech.ChapterScript"),
    )
    val FORBIDDEN_DOMAIN_PACKAGES = listOf(d("repository"), d("source"))

    private fun under(name: String, type: String) = name == type || name.startsWith("$type.")

    /** presentation의 [set] 소스셋이 domain 이름 [name](완전한 이름, 와일드카드 `.*` 가능)을 참조해도 되는가 */
    fun presentationMayUse(name: String, set: String): Boolean {
        if (FORBIDDEN_DOMAIN_TYPES.any { under(name, it) }) return false
        if (set == "debug" && PRESENTATION_DOMAIN_TYPES_DEBUG.any { under(name, it) }) return true
        if (FORBIDDEN_DOMAIN_PACKAGES.any { name.startsWith("$it.") }) return false
        return PRESENTATION_DOMAIN_PACKAGES.any { name.startsWith("$it.") } || PRESENTATION_DOMAIN_TYPES.any { under(name, it) }
    }

    /**
     * presentation(모든 소스셋)의 domain 참조는 허용 목록 안이어야 한다: import·본문 FQ 참조,
     * 허용 밖 패키지의 와일드카드 import 뒤 단순 이름(그 패키지의 최상위 선언 + 명시 금지 이름).
     */
    fun presentationUsesNonAllowedDomain(src: List<ScannedSource>): List<String> {
        val declared = declaredByPackage(src.filter { it.layer == "domain" })
        return src.filter { it.layer == "presentation" }.flatMap { s ->
            val body by lazy { stripHeader(s) }
            projectReferences(s).filter { (_, n) -> layerOf(n) == "domain" && !presentationMayUse(n, s.set) }.flatMap { (desc, n) ->
                if (n.endsWith(".*")) {
                    val pkg = n.removeSuffix(".*")
                    val names = declared[pkg].orEmpty() +
                        FORBIDDEN_DOMAIN_TYPES.filter { it.substringBeforeLast('.') == pkg }.map { it.substringAfterLast('.') }
                    names.filter { !presentationMayUse("$pkg.$it", s.set) && word(it).containsMatchIn(body) }
                        .map { "${s.path}: $desc 뒤 이름 $it" }
                } else {
                    listOf("${s.path}: $desc")
                }
            }
        }
    }

    private fun forbiddenAliasTarget(fq: String) =
        FORBIDDEN_DOMAIN_TYPES.any { under(fq, it) } || FORBIDDEN_DOMAIN_PACKAGES.any { fq.startsWith("$it.") }

    private val typealiasDecl = Regex("""\btypealias\s+(\w+)\s*(?:<[^=\n]*>)?\s*=\s*([^\n;]+)""")
    private val identifierPath = Regex("""[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*""")

    /** domain 어디서든 `typealias X = <금지 타입>`(명시 금지 타입, domain.repository·domain.source) 금지(별칭으로 허용 목록 우회 방지). */
    fun domainAliasesForbiddenType(src: List<ScannedSource>): List<String> {
        val forbiddenSimple = FORBIDDEN_DOMAIN_TYPES.map { it.substringAfterLast('.') }.toSet()
        return src.filter { it.layer == "domain" }.flatMap { s ->
            typealiasDecl.findAll(s.stripped).filter { m ->
                identifierPath.findAll(m.groupValues[2]).any { t ->
                    val head = t.value.substringBefore('.')
                    val fq = when {
                        t.value.startsWith("$ROOT.") -> t.value
                        else -> s.imports.firstOrNull { it.endsWith(".$head") }?.let { it + t.value.removePrefix(head) }
                            ?: "${s.packageName}.${t.value}"
                    }
                    forbiddenAliasTarget(fq) || head in forbiddenSimple
                }
            }.map { "${s.path}: typealias ${it.groupValues[1]} = ${it.groupValues[2].trim()}" }.toList()
        }
    }

    private val useCaseClass = Regex("""\bclass\s+(\w+UseCase)\b""")

    /** domain/usecase의 `…UseCase` 주 생성자 속성은 `private val`만(`val`·`var` 공개 속성으로 세션 내부가 새지 않게). */
    fun useCasePublicConstructorProperties(src: List<ScannedSource>): List<String> =
        src.filter { it.layer == "domain" && it.rel.startsWith("domain/usecase/") }.flatMap { s ->
            useCaseClass.findAll(s.stripped).flatMap { m ->
                primaryConstructorParams(s.stripped, m.range.last + 1).filter { p ->
                    val head = p.substringBefore(':')
                    Regex("""\b(val|var)\b""").containsMatchIn(head) &&
                        !(Regex("""\bprivate\b""").containsMatchIn(head) && Regex("""\bval\b""").containsMatchIn(head))
                }.map { "${s.path}: ${m.groupValues[1]}(${it.trim()})" }
            }.toList()
        }

    /** `class Name<…> 수식어 constructor(…)`의 괄호 안 매개변수(깊이 0 쉼표로 나눔). 주 생성자가 없으면 빈 목록. */
    private fun primaryConstructorParams(text: String, from: Int): List<String> {
        var i = from
        fun skipSpaces() {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        }
        fun skipBalanced(open: Char, close: Char) {
            var depth = 0
            while (i < text.length) {
                if (text[i] == open) depth++
                if (text[i] == close) { depth--; if (depth == 0) { i++; return } }
                i++
            }
        }
        skipSpaces()
        if (i < text.length && text[i] == '<') skipBalanced('<', '>')
        skipSpaces()
        // 생성자 수식어·어노테이션(예: `@Inject constructor`, `@Named( ) internal constructor`)
        while (i < text.length && (text[i].isLetter() || text[i] == '@')) {
            val annotation = text[i] == '@'
            while (i < text.length && !text[i].isWhitespace() && text[i] != '(') i++
            if (annotation && i < text.length && text[i] == '(') skipBalanced('(', ')')
            skipSpaces()
        }
        if (i >= text.length || text[i] != '(') return emptyList()
        val params = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        i++
        while (i < text.length) {
            val c = text[i]
            if (c == '-' && i + 1 < text.length && text[i + 1] == '>') { cur.append("->"); i += 2; continue }
            if (c == '(' || c == '<' || c == '[' || c == '{') depth++
            if (c == ')' || c == '>' || c == ']' || c == '}') {
                if (depth == 0 && c == ')') break
                depth--
            }
            if (depth == 0 && c == ',') { params += cur.toString(); cur.clear() } else cur.append(c)
            i++
        }
        params += cur.toString()
        return params.filter { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- 변형(빌드 타입·flavor) 전용 타입

    /** 언제나 있는 빌드 타입 소스셋(디렉터리가 없어도 변형은 있다) */
    private val BUILD_TYPES = setOf("debug", "release")

    /**
     * main에 없고 모든 변형 소스셋(스캔한 main 밖 소스셋 + debug·release)에 다 있지는 않은 최상위 선언의 완전한 이름.
     * 예: debug에만 있는 `domain.source.VehicleSimulator`. debug·release 양쪽에 있는 `DebugPanel`·`VariantModule`은 빠진다.
     */
    fun variantOnlyNames(src: List<ScannedSource>): Set<String> {
        val bySet = src.filter { it.layer != "outside" && it.packageName != null }.groupBy { it.set }
            .mapValues { (_, l) -> l.flatMap { s -> topLevelNames(s).map { "${s.packageName}.$it" } }.toSet() }
        val main = bySet["main"].orEmpty()
        val variantSets = (bySet.keys - "main") + BUILD_TYPES
        return (bySet - "main").values.flatten()
            .filter { n -> n !in main && variantSets.any { n !in bySet[it].orEmpty() } }
            .toSet()
    }

    /** main이 변형 전용 선언을 참조하지 않는다(import·FQ 참조, 와일드카드 import·같은 패키지의 단순 이름). */
    fun mainUsesVariantOnlyType(src: List<ScannedSource>): List<String> {
        val only = variantOnlyNames(src)
        if (only.isEmpty()) return emptyList()
        return src.filter { it.set == "main" && it.layer != "outside" }.flatMap { s ->
            val byName = projectReferences(s).filter { (_, n) -> only.any { under(n, it) } }.map { "${s.path}: ${it.first}" }
            val visiblePackages = s.imports.filter { it.endsWith(".*") }.map { it.removeSuffix(".*") } + listOfNotNull(s.packageName)
            val body = stripHeader(s)
            val bySimple = only.filter { it.substringBeforeLast('.') in visiblePackages && word(it.substringAfterLast('.')).containsMatchIn(body) }
                .map { "${s.path}: 이름 $it" }
            byName + bySimple
        }
    }

    // ---------------------------------------------------------------- 이름·MVI 규칙

    private const val MODS = """(?:(?:public|internal|private|protected|data|sealed|abstract|open|final|value|inline|enum|annotation|inner|fun|expect|actual)\s+)*"""
    private val topLevelClass = Regex("""(?m)^$MODS(class)\s+(\w+)""")
    private val topLevelDecl = Regex("""(?m)^($MODS)(class|interface|object)\s+(\w+)""")

    /** domain/usecase의 최상위 클래스 이름은 UseCase로 끝난다(인터페이스·object는 제외). */
    fun useCaseNaming(src: List<ScannedSource>): List<String> =
        src.filter { it.layer == "domain" && it.rel.startsWith("domain/usecase/") }.flatMap { s ->
            topLevelClass.findAll(s.stripped).map { it.groupValues[2] }.filterNot { it.endsWith("UseCase") }
                .map { "${s.path}: class $it" }.toList()
        }

    private val classOrObjectHeader = Regex("""(?<!::)\b(class|object)[ \t]+(\w+)""")

    /** data에서 domain.repository 인터페이스를 구현하는 클래스·object는 Impl로 끝난다. */
    fun repositoryImplNaming(src: List<ScannedSource>): List<String> {
        val repoNames = src.filter { it.rel.startsWith("domain/repository/") }
            .flatMap { s -> Regex("""(?m)^$MODS(?:fun\s+)?interface\s+(\w+)""").findAll(s.stripped).map { it.groupValues[1] }.toList() }
            .toSet()
        if (repoNames.isEmpty()) return emptyList()
        return src.filter { it.layer == "data" }.flatMap { s ->
            classOrObjectHeader.findAll(s.stripped).mapNotNull { m ->
                val name = m.groupValues[2]
                val supers = supertypes(s.stripped, m.range.last + 1)
                val implemented = repoNames.filter { Regex("""(?<![\w])$it\b""").containsMatchIn(supers) }
                if (implemented.isNotEmpty() && !name.endsWith("Impl")) "${s.path}: ${m.groupValues[1]} $name : $implemented" else null
            }.toList()
        }
    }

    /** `class Name<…>(…) : A, B {` 에서 `:` 뒤 상위 타입 부분. 괄호 깊이 0의 `{`나 이어지지 않는 줄바꿈에서 멈춘다. */
    private fun supertypes(text: String, from: Int): String {
        var i = from
        fun skipSpaces(newlines: Boolean) {
            while (i < text.length && (text[i] == ' ' || text[i] == '\t' || newlines && text[i] == '\n')) i++
        }
        fun skipBalanced(open: Char, close: Char) {
            var d = 0
            while (i < text.length) {
                if (text[i] == open) d++
                if (text[i] == close) { d--; if (d == 0) { i++; return } }
                i++
            }
        }
        skipSpaces(false)
        if (i < text.length && text[i] == '<') skipBalanced('<', '>')
        skipSpaces(false)
        // 주 생성자 수식어·어노테이션(예: `private constructor`, `@Inject constructor`)
        while (i < text.length && (text[i].isLetter() || text[i] == '@')) {
            while (i < text.length && !text[i].isWhitespace() && text[i] != '(') i++
            skipSpaces(false)
        }
        if (i < text.length && text[i] == '(') skipBalanced('(', ')')
        skipSpaces(true)
        if (i >= text.length || text[i] != ':') return ""
        val sb = StringBuilder()
        i++
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '(' || c == '<') depth++
            if (c == ')' || c == '>') depth--
            if (depth == 0 && c == '{') break
            if (depth == 0 && c == '\n' && sb.isNotBlank() && !sb.trimEnd().endsWith(",")) break
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** presentation의 …Action.kt·…Event.kt 최상위 타입 선언은 sealed interface(하나 이상). */
    fun actionAndEventShape(src: List<ScannedSource>): List<String> =
        src.filter { it.layer == "presentation" && (it.rel.endsWith("Action.kt") || it.rel.endsWith("Event.kt")) }.flatMap { s ->
            val decls = topLevelDecl.findAll(s.stripped).toList()
            if (decls.isEmpty()) {
                listOf("${s.path}: 최상위 선언 없음")
            } else {
                decls.filterNot { it.groupValues[2] == "interface" && Regex("""\bsealed\b""").containsMatchIn(it.groupValues[1]) }
                    .map { "${s.path}: ${it.value.trim()} (sealed interface 아님)" }
            }
        }

    /** presentation의 …UiState.kt에 있는 파일 이름과 같은 …UiState는 data class. */
    fun uiStateShape(src: List<ScannedSource>): List<String> =
        src.filter { it.layer == "presentation" && it.rel.endsWith("UiState.kt") }.mapNotNull { s ->
            val name = s.rel.substringAfterLast('/').removeSuffix(".kt")
            val decl = topLevelDecl.findAll(s.stripped).firstOrNull { it.groupValues[3] == name }
            when {
                decl == null -> "${s.path}: $name 선언 없음"
                decl.groupValues[2] != "class" || !Regex("""\bdata\b""").containsMatchIn(decl.groupValues[1]) ->
                    "${s.path}: ${decl.value.trim()} (data class 아님)"
                else -> null
            }
        }
}
