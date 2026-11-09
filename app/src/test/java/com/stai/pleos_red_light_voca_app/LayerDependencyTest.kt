package com.stai.pleos_red_light_voca_app

import com.stai.pleos_red_light_voca_app.LayerScanner.ROOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 계층 의존 방향·이름 규칙 검사(소스 스캔, [LayerScanner]).
 *
 * 대상: `app/src/` 아래 이름이 `test`·`androidTest`로 시작하지 않는 모든 소스셋(지금 main·debug·release, 이후 flavor 포함)의
 * `java/`·`kotlin/` 아래 모든 `.kt`·`.java`. 패키지 디렉터리 밖 파일은 위반이다. 계층은 디렉터리로 정하고,
 * package 선언이 디렉터리와 같은지도 본다.
 * test·testDebug·androidTest는 검사하지 않는다. 테스트는 실제 객체를 조립하려고 계층을 넘어
 * (예: ViewModel 테스트가 domain 세션과 가짜 저장소를 함께) 참조할 수 있고, 앱 실행 코드에 들어가지 않는다.
 *
 * 전처리: 주석·문자열·문자 리터럴을 지우고 백틱을 벗기고 점 앞뒤 줄바꿈을 이은 본문에서 import(한 줄 다중·별칭·와일드카드·줄 넘김),
 * 본문의 완전한 이름 참조(typealias 우변 포함), 플랫폼 접두어 참조를 찾는다.
 * 루트 패키지의 생성 클래스 `R`·`BuildConfig`는 어느 계층에서 참조해도 위반이 아니다.
 *
 * - presentation → domain ← data, 모든 계층 → core. core는 다른 계층을 보지 않는다.
 * - presentation은 data를, data는 presentation을 보지 않는다. 둘 다 di·루트를 보지 않는다.
 * - presentation은 domain UseCase를 거친다: 허용 목록([LayerScanner.PRESENTATION_DOMAIN_PACKAGES]·
 *   [LayerScanner.PRESENTATION_DOMAIN_TYPES], debug만 [LayerScanner.PRESENTATION_DOMAIN_TYPES_DEBUG]) 밖 domain을 보지 않는다.
 *   `AppSession`·`LearningController`·`StopGate`·`PlayerControlRelay`·`ChapterScript`·`domain.repository`·`domain.source`는 명시 금지.
 * - domain은 금지 타입의 typealias를 두지 않고, UseCase 생성자 속성은 `private val`만 둔다(허용 목록 우회 방지).
 * - di는 조립만 한다: di와 루트(VocaApplication)만 di를 참조할 수 있다.
 * - 루트는 di·domain·core와 플랫폼만 본다(data·presentation·Timber 금지).
 * - core·domain은 플랫폼 코드(Android/androidx/Media3/Pleos/Timber/Hilt·Dagger/javax·jakarta.inject/Guava/org.json/dalvik/
 *   kotlinx.coroutines.android)에 의존하지 않고 `Dispatchers.Main`을 쓰지 않는다.
 * - Timber는 data·presentation·di에서만 쓴다.
 * - main은 변형(debug·release·flavor) 한쪽에만 있는 최상위 선언을 보지 않는다(양쪽에 다 있으면 허용).
 * - 이름: `domain/usecase` 클래스는 `…UseCase`, data의 저장소 구현은 `…Impl`,
 *   presentation `…Action`·`…Event`는 sealed interface, `…UiState`는 data class.
 */
class LayerDependencyTest {

    private val sources: List<ScannedSource> by lazy { LayerScanner.scanProject() }

    private fun assertNone(rule: String, found: List<String>) =
        assertTrue("$rule\n" + found.joinToString("\n"), found.isEmpty())

    // ------------------------------------------------------------ 실제 소스 검사

    @Test fun sources_are_found_in_every_layer() {
        val seen = sources.map { it.layer }.toSet()
        assertTrue("found layers: $seen", seen.containsAll(LayerScanner.LAYERS))
    }

    @Test fun core_depends_on_no_other_layer() =
        assertNone("core는 다른 계층을 보지 않는다", LayerScanner.coreDependsOnOtherLayer(sources))

    @Test fun domain_depends_only_on_core() =
        assertNone("domain은 core만 본다", LayerScanner.domainDependsOnNonCore(sources))

    @Test fun data_does_not_depend_on_presentation_or_di() =
        assertNone("data는 presentation·di·루트를 보지 않는다", LayerScanner.dataDependsOnPresentationOrDi(sources))

    @Test fun presentation_does_not_depend_on_data_or_di() =
        assertNone("presentation은 data·di·루트를 보지 않는다", LayerScanner.presentationDependsOnDataOrDi(sources))

    @Test fun core_and_domain_have_no_platform_imports() =
        assertNone("core·domain은 플랫폼 코드에 의존하지 않는다(import·본문 참조)", LayerScanner.coreOrDomainUsesPlatform(sources))

    @Test fun timber_is_used_only_in_data_presentation_di() =
        assertNone("Timber는 data·presentation·di에서만(루트 금지)", LayerScanner.timberOutsideAllowedLayers(sources))

    @Test fun di_is_imported_only_by_di_and_root() =
        assertNone("di는 조립만: di·루트 밖에서 di를 참조하지 않는다", LayerScanner.diUsedOutsideDi(sources))

    @Test fun every_main_source_is_inside_a_layer() {
        val stray = sources.filter { it.layer == "root" && it.path != "main/VocaApplication.kt" }.map { it.path }
        assertTrue("계층 밖 소스: $stray", stray.isEmpty())
    }

    @Test fun root_depends_only_on_di_domain_core_and_platform() =
        assertNone("루트는 data·presentation을 보지 않는다", LayerScanner.rootDependsOnDataOrPresentation(sources))

    @Test fun package_matches_directory() =
        assertNone("package 선언은 디렉터리와 같다", LayerScanner.packageMismatch(sources))

    @Test fun presentation_does_not_use_app_session_or_learning_controller() =
        assertNone("presentation은 AppSession·LearningController를 보지 않는다(UseCase만)", LayerScanner.presentationUsesSessionInternals(sources))

    @Test fun use_case_classes_end_with_use_case() =
        assertNone("domain/usecase의 최상위 클래스는 …UseCase", LayerScanner.useCaseNaming(sources))

    @Test fun repository_implementations_end_with_impl() =
        assertNone("data의 저장소 구현은 …Impl", LayerScanner.repositoryImplNaming(sources))

    @Test fun actions_and_events_are_sealed_interfaces() =
        assertNone("presentation …Action·…Event는 sealed interface", LayerScanner.actionAndEventShape(sources))

    @Test fun ui_states_are_data_classes() =
        assertNone("presentation …UiState는 data class", LayerScanner.uiStateShape(sources))

    @Test fun presentation_references_only_allowed_domain_types() =
        assertNone("presentation은 허용 목록(UseCase·값 타입) 밖 domain을 보지 않는다", LayerScanner.presentationUsesNonAllowedDomain(sources))

    @Test fun domain_has_no_typealias_of_forbidden_types() =
        assertNone("domain은 금지 타입의 typealias를 두지 않는다", LayerScanner.domainAliasesForbiddenType(sources))

    @Test fun use_case_constructor_properties_are_private() =
        assertNone("UseCase 생성자 속성은 private val만", LayerScanner.useCasePublicConstructorProperties(sources))

    @Test fun every_source_is_inside_the_package_directory() =
        assertNone("소스는 패키지 디렉터리(com/stai/pleos_red_light_voca_app) 안에 둔다", LayerScanner.filesOutsidePackageDir(sources))

    @Test fun core_and_domain_do_not_use_main_dispatcher() =
        assertNone("core·domain은 Dispatchers.Main을 쓰지 않는다", LayerScanner.coreOrDomainUsesMainDispatcher(sources))

    @Test fun main_does_not_use_variant_only_types() =
        assertNone("main은 변형 한쪽에만 있는 선언을 보지 않는다", LayerScanner.mainUsesVariantOnlyType(sources))

    @Test fun presentation_allow_list_names_existing_value_types() {
        // 허용 목록이 낡지 않았는지(이름이 바뀌면 목록도 고친다), 명시 금지와 겹치지 않는지
        val declared = sources.filter { it.layer == "domain" }
            .flatMap { s -> LayerScanner.topLevelNames(s).map { "${s.packageName}.$it" } }.toSet()
        val listed = LayerScanner.PRESENTATION_DOMAIN_TYPES + LayerScanner.PRESENTATION_DOMAIN_TYPES_DEBUG
        assertTrue("선언 없음: ${listed - declared}", declared.containsAll(listed))
        assertTrue(listed.none { it in LayerScanner.FORBIDDEN_DOMAIN_TYPES })
        assertTrue(LayerScanner.PRESENTATION_DOMAIN_TYPES.none { t -> LayerScanner.FORBIDDEN_DOMAIN_PACKAGES.any { t.startsWith("$it.") } })
    }

    @Test fun scanner_sees_every_non_test_source_set() {
        val sets = sources.map { it.set }.toSet()
        assertTrue("sets: $sets", sets.containsAll(listOf("main", "debug", "release")))
        assertTrue("sets: $sets", sets.none { it.startsWith("test") || it.startsWith("androidTest") })
        // 지금 변형 전용으로 잡히는 선언이 있어야 변형 규칙이 헛돌지 않는다
        assertTrue(LayerScanner.variantOnlyNames(sources).contains("$ROOT.domain.source.VehicleSimulator"))
        assertTrue(LayerScanner.variantOnlyNames(sources).none { it.endsWith(".DebugPanel") || it.endsWith(".VariantModule") })
    }

    @Test fun scanner_sees_debug_sources_and_naming_targets() {
        // 이름 규칙이 실제로 대상을 보고 있는지(대상이 비면 규칙이 헛돈다)
        assertTrue(sources.any { it.path.startsWith("debug/") })
        assertTrue(sources.any { it.rel.startsWith("domain/usecase/") })
        assertTrue(sources.any { it.rel.startsWith("domain/repository/") })
        assertTrue(sources.any { it.layer == "presentation" && it.rel.endsWith("Action.kt") })
        assertTrue(sources.any { it.layer == "presentation" && it.rel.endsWith("UiState.kt") })
    }

    // ------------------------------------------------------------ 스캐너 자체 검증(리뷰 반례)

    private fun src(rel: String, text: String, set: String = "main") = LayerScanner.source(set, rel, text)

    private fun pkgOf(rel: String) = ROOT + "." + rel.substringBeforeLast('/').replace('/', '.')

    private fun kt(rel: String, body: String) = src(rel, "package ${pkgOf(rel)}\n\n$body\n")

    @Test fun scanner_catches_multiple_imports_on_one_line() {
        val s = kt("core/Leak.kt", "import kotlin.math.abs; import timber.log.Timber; import $ROOT.data.log.TimberLogger")
        assertEquals(listOf("kotlin.math.abs", "timber.log.Timber", "$ROOT.data.log.TimberLogger"), s.imports)
        assertEquals(1, LayerScanner.coreDependsOnOtherLayer(listOf(s)).size)
        assertEquals(1, LayerScanner.coreOrDomainUsesPlatform(listOf(s)).size)
        assertEquals(1, LayerScanner.timberOutsideAllowedLayers(listOf(s)).size)
    }

    @Test fun scanner_catches_backtick_import() {
        val s = kt("core/Leak.kt", "import `android`.util.Log\nimport $ROOT.`data`.log.TimberLogger")
        assertEquals(listOf("android.util.Log", "$ROOT.data.log.TimberLogger"), s.imports)
        assertEquals(1, LayerScanner.coreOrDomainUsesPlatform(listOf(s)).size)
        assertEquals(1, LayerScanner.coreDependsOnOtherLayer(listOf(s)).size)
    }

    @Test fun scanner_catches_fully_qualified_references() {
        val s = kt(
            "domain/leak/FqLeak.kt",
            """
            class FqLeak @javax.inject.Inject constructor() {
                fun log(m: String) {
                    timber.log.Timber.i(m)
                    android.util.Log.i("t", m)
                    val r: $ROOT.data.catalog.CatalogRepositoryImpl? = null
                    val v = $ROOT.presentation.learning.LearningViewModel::class
                }
            }
            """.trimIndent(),
        )
        assertTrue(s.imports.isEmpty())
        val layer = LayerScanner.domainDependsOnNonCore(listOf(s))
        assertEquals(layer.toString(), 2, layer.size)
        val platform = LayerScanner.coreOrDomainUsesPlatform(listOf(s))
        assertEquals(platform.toString(), 3, platform.size)
        assertEquals(1, LayerScanner.timberOutsideAllowedLayers(listOf(s)).size)
    }

    @Test fun scanner_catches_typealias_right_hand_side() {
        val s = kt("domain/leak/Alias.kt", "typealias LeakedRepo = $ROOT.data.catalog.CatalogRepositoryImpl\ntypealias Di = $ROOT.di.AppModule")
        assertEquals(2, LayerScanner.domainDependsOnNonCore(listOf(s)).size)
        assertEquals(1, LayerScanner.diUsedOutsideDi(listOf(s)).size)
    }

    @Test fun scanner_catches_java_imports() {
        val s = src(
            "domain/JavaLeak.java",
            "package $ROOT.domain;\n\nimport android.util.Log; import static $ROOT.data.log.TimberLogger.X;\n" +
                "/* import ok.Comment; */\nclass JavaLeak { String s = \"import android.x.Y;\"; char c = '\"'; }\n",
        )
        assertEquals("$ROOT.domain", s.packageName)
        assertEquals(listOf("android.util.Log", "$ROOT.data.log.TimberLogger.X"), s.imports)
        assertEquals(1, LayerScanner.coreOrDomainUsesPlatform(listOf(s)).size)
        assertEquals(1, LayerScanner.domainDependsOnNonCore(listOf(s)).size)
        assertTrue(LayerScanner.packageMismatch(listOf(s)).isEmpty())
    }

    @Test fun scanner_catches_package_not_matching_directory() {
        val bad = src("presentation/x/Foo.kt", "package $ROOT.data.catalog\n\nclass Foo(val r: CatalogRepositoryImpl)\n")
        val none = src("presentation/x/Bar.kt", "class Bar\n")
        val good = kt("presentation/x/Ok.kt", "class Ok")
        val rootFile = src("VocaApplication.kt", "package $ROOT\n\nclass VocaApplication\n")
        assertEquals(2, LayerScanner.packageMismatch(listOf(bad, none, good, rootFile)).size)
    }

    @Test fun scanner_ignores_imports_in_strings_and_comments() {
        val q = "\"\"\""
        val s = kt(
            "core/Text.kt",
            """
            /**
             * KDoc: import android.util.Log, timber.log.Timber.i("x"), $ROOT.data.X
             * /* 중첩 주석 import dagger.Module */ 여전히 주석 android.os.Build
             */
            object Text {
                // import timber.log.Timber
                const val PERM = "android.car.permission.CAR_SPEED"
                val raw = $q
                    import android.util.Log
                    timber.log.Timber.i("${'$'}{x}")
                $q
                val esc = "\"import android.x.Y\" ${'$'}{1 + 1} \\"
                val ch = '"'
                val ch2 = '\''
                val after = 1 // android.after
                val flow = "-> LearningController"
            }
            """.trimIndent(),
        )
        assertTrue(s.imports.toString(), s.imports.isEmpty())
        assertTrue(s.platformRefs.toString(), s.platformRefs.isEmpty())
        assertTrue(s.projectRefs.toString(), s.projectRefs.isEmpty())
        assertTrue(LayerScanner.coreOrDomainUsesPlatform(listOf(s)).isEmpty())
        // 리터럴 뒤의 코드는 그대로 남는다
        assertTrue(s.stripped.contains("val after = 1"))
        assertTrue(s.stripped.contains("val ch2 ="))
    }

    @Test fun scanner_still_checks_code_inside_string_templates() {
        val s = kt("domain/T.kt", "val m = \"x ${'$'}{$ROOT.data.Y.z} ${'$'}{android.os.Build.MODEL}\"")
        assertEquals(1, LayerScanner.domainDependsOnNonCore(listOf(s)).size)
        assertEquals(1, LayerScanner.coreOrDomainUsesPlatform(listOf(s)).size)
    }

    @Test fun scanner_allows_generated_r_and_build_config() {
        val p = kt("presentation/x/Ui.kt", "import $ROOT.R\nval t = R.string.app_name")
        val d = kt("data/x/D.kt", "import $ROOT.BuildConfig\nval dbg = $ROOT.BuildConfig.DEBUG\nval s = $ROOT.R.string.app_name")
        val c = kt("core/x/C.kt", "import $ROOT.R")
        val all = listOf(p, d, c)
        assertTrue(LayerScanner.presentationDependsOnDataOrDi(all).isEmpty())
        assertTrue(LayerScanner.dataDependsOnPresentationOrDi(all).isEmpty())
        assertTrue(LayerScanner.coreDependsOnOtherLayer(all).isEmpty())
        // 루트의 다른 클래스는 여전히 위반
        val v = kt("presentation/x/V.kt", "import $ROOT.VocaApplication")
        assertEquals(1, LayerScanner.presentationDependsOnDataOrDi(listOf(v)).size)
    }

    @Test fun scanner_catches_wildcard_and_alias_imports() {
        val s = kt("core/W.kt", "import $ROOT.data.*\nimport timber.log.Timber as T\nimport $ROOT.di.AppModule as M")
        assertEquals(listOf("$ROOT.data.*", "timber.log.Timber", "$ROOT.di.AppModule"), s.imports)
        assertEquals(2, LayerScanner.coreDependsOnOtherLayer(listOf(s)).size)
        assertEquals(1, LayerScanner.timberOutsideAllowedLayers(listOf(s)).size)
        assertEquals(1, LayerScanner.diUsedOutsideDi(listOf(s)).size)
    }

    @Test fun scanner_checks_root_package_rules() {
        val bad = src(
            "VocaApplication.kt",
            "package $ROOT\nimport timber.log.Timber\nimport $ROOT.data.log.VocaLogTree\nimport $ROOT.presentation.MainActivity\n",
        )
        val ok = src(
            "VocaApplication.kt",
            "package $ROOT\nimport android.app.Application\nimport $ROOT.di.plantLogTree\nimport $ROOT.domain.usecase.StartAppUseCase\nimport dagger.hilt.android.HiltAndroidApp\n",
        )
        assertEquals(2, LayerScanner.rootDependsOnDataOrPresentation(listOf(bad)).size)
        assertEquals(1, LayerScanner.timberOutsideAllowedLayers(listOf(bad)).size)
        assertTrue(LayerScanner.rootDependsOnDataOrPresentation(listOf(ok)).isEmpty())
        assertTrue(LayerScanner.timberOutsideAllowedLayers(listOf(ok)).isEmpty())
        assertTrue(LayerScanner.diUsedOutsideDi(listOf(ok)).isEmpty())
    }

    @Test fun scanner_catches_presentation_use_of_session_internals() {
        val byImport = kt("presentation/a/A.kt", "import $ROOT.domain.session.AppSession")
        val byWildcard = kt("presentation/a/B.kt", "import $ROOT.domain.learning.*\nclass B(val c: LearningController)")
        val byFq = kt("presentation/a/C.kt", "class C(val s: $ROOT.domain.session.AppSession)")
        val inString = kt("presentation/a/D.kt", "val m = \"Play -> LearningController\" // AppSession\nval u = 1")
        val inData = kt("data/a/E.kt", "import $ROOT.domain.session.AppSession")
        assertEquals(1, LayerScanner.presentationUsesSessionInternals(listOf(byImport)).size)
        assertTrue(LayerScanner.presentationUsesSessionInternals(listOf(byWildcard)).isNotEmpty())
        assertTrue(LayerScanner.presentationUsesSessionInternals(listOf(byFq)).isNotEmpty())
        assertTrue(LayerScanner.presentationUsesSessionInternals(listOf(inString, inData)).isEmpty())
    }

    @Test fun scanner_catches_domain_reference_outside_presentation_allow_list() {
        val ok = kt(
            "presentation/a/Ok.kt",
            "import $ROOT.domain.usecase.StartLearningUseCase\nimport $ROOT.domain.session.SessionState\n" +
                "import $ROOT.domain.usecase.*\nval r = $ROOT.domain.session.SessionState.Ready::class",
        )
        val repo = kt("presentation/a/Repo.kt", "import $ROOT.domain.repository.ProgressRepository")
        val gate = kt("presentation/a/Gate.kt", "class G(val g: $ROOT.domain.gate.StopGate)")
        val script = kt("presentation/a/Script.kt", "import $ROOT.domain.speech.ChapterScript")
        val relay = kt("presentation/a/Relay.kt", "import $ROOT.domain.session.PlayerControlRelay")
        val unknown = kt("presentation/a/Unknown.kt", "import $ROOT.domain.learning.PlayDecision")
        val sourceMain = kt("presentation/a/Sim.kt", "import $ROOT.domain.source.VehicleSimulator")
        val sourceDebug = src("presentation/a/Sim.kt", "package $ROOT.presentation.a\nimport $ROOT.domain.source.VehicleSimulator\nimport $ROOT.domain.source.SourceMode", set = "debug")
        val sourceDebugBad = src("presentation/a/Src.kt", "package $ROOT.presentation.a\nimport $ROOT.domain.source.VehicleStateSource", set = "debug")
        // 와일드카드: 그 패키지에 선언된 이름 중 허용 밖 이름을 쓰면 위반
        val domainDecl = kt("domain/learning/Types.kt", "data class LearningState(val a: Int)\nsealed interface PlayDecision\nclass LearningController")
        val wildOk = kt("presentation/a/W1.kt", "import $ROOT.domain.learning.*\nval s: LearningState? = null")
        val wildBad = kt("presentation/a/W2.kt", "import $ROOT.domain.learning.*\nval d: PlayDecision? = null")
        fun found(vararg s: ScannedSource) = LayerScanner.presentationUsesNonAllowedDomain(listOf(domainDecl, *s))
        assertTrue(found(ok).toString(), found(ok).isEmpty())
        listOf(repo, gate, script, relay, unknown, sourceMain, sourceDebugBad, wildBad).forEach {
            assertEquals(it.path, 1, found(it).size)
        }
        assertTrue(found(sourceDebug).isEmpty())
        assertTrue(found(wildOk).isEmpty())
        // presentation 밖은 이 규칙의 대상이 아니다
        assertTrue(found(kt("data/a/D.kt", "import $ROOT.domain.repository.ProgressRepository")).isEmpty())
    }

    @Test fun scanner_catches_typealias_to_forbidden_type() {
        val bySimple = kt("domain/session/Alias.kt", "typealias Session = AppSession")
        val byImport = kt("domain/learning/Alias.kt", "import $ROOT.domain.repository.ProgressRepository\ntypealias Repo = ProgressRepository")
        val byFq = kt("domain/usecase/Alias.kt", "typealias Gate = $ROOT.domain.gate.StopGate")
        val inFunctionType = kt("domain/usecase/Fn.kt", "import $ROOT.domain.learning.LearningController\ntypealias Fn = (LearningController) -> Unit")
        val valueAlias = kt("domain/learning/Ok.kt", "enum class PlaybackEnd { A }\ntypealias PauseReason = PlaybackEnd\ntypealias Ids = List<String>")
        assertEquals(4, LayerScanner.domainAliasesForbiddenType(listOf(bySimple, byImport, byFq, inFunctionType)).size)
        assertTrue(LayerScanner.domainAliasesForbiddenType(listOf(valueAlias)).isEmpty())
    }

    @Test fun scanner_catches_public_use_case_property() {
        val bad = kt(
            "domain/usecase/Bad.kt",
            "class AUseCase(val session: AppSession)\n" +
                "class BUseCase @Inject constructor(\n    private val a: Int,\n    var b: Map<String, Int>,\n)\n" +
                "class CUseCase(private var c: Int, internal val d: Int)",
        )
        val ok = kt(
            "domain/usecase/Ok.kt",
            "class OkUseCase(\n    private val session: AppSession,\n    private val f: (Int) -> Unit = {},\n    plain: Int,\n) {\n    val exposed = 1\n}\n" +
                "data class Report(val x: Int)",
        )
        val found = LayerScanner.useCasePublicConstructorProperties(listOf(bad))
        assertEquals(found.toString(), 4, found.size)
        assertTrue(LayerScanner.useCasePublicConstructorProperties(listOf(ok)).isEmpty())
    }

    @Test fun scanner_catches_files_outside_package_directory() {
        val base = newTempDir()
        try {
            fun put(path: String, text: String) = File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }
            val pkgPath = ROOT.replace('.', '/')
            put("main/java/$pkgPath/core/Ok.kt", "package $ROOT.core\n")
            put("main/java/Leak.kt", "package $ROOT.domain\nimport android.content.Context\n")
            put("main/kotlin/$pkgPath/domain/K.kt", "package $ROOT.domain\nimport android.content.Context\n")
            put("pleos/java/com/other/X.java", "package com.other;\n")
            put("test/java/$pkgPath/T.kt", "package $ROOT\n")
            put("testDebug/java/Out.kt", "package x\n")
            put("androidTest/java/Out.kt", "package x\n")
            val scanned = LayerScanner.scanProject(base)
            assertEquals(setOf("main", "pleos"), scanned.map { it.set }.toSet())
            assertEquals(listOf("main/java/Leak.kt", "pleos/java/com/other/X.java"), LayerScanner.filesOutsidePackageDir(scanned).map { it.substringBefore(':') }.sorted())
            // kotlin/ 루트도 본다
            assertEquals(1, LayerScanner.coreOrDomainUsesPlatform(scanned).size)
        } finally {
            base.deleteRecursively()
        }
    }

    private fun newTempDir(): File = java.nio.file.Files.createTempDirectory("layer-scan").toFile()

    @Test fun scanner_joins_import_split_across_lines() {
        val s = kt(
            "core/Split.kt",
            "import $ROOT\n    .data.catalog.CatalogRepositoryImpl\nimport android.\n    util.Log\nval x = 1",
        )
        assertEquals(listOf("$ROOT.data.catalog.CatalogRepositoryImpl", "android.util.Log"), s.imports)
        assertEquals(1, LayerScanner.coreDependsOnOtherLayer(listOf(s)).size)
        assertEquals(1, LayerScanner.coreOrDomainUsesPlatform(listOf(s)).size)
        val pkg = src("core/P.kt", "package $ROOT\n    .core\n")
        assertEquals("$ROOT.core", pkg.packageName)
        // 본문의 줄 넘긴 완전한 이름도 잇는다
        val body = kt("domain/B.kt", "val c = $ROOT.data\n    .catalog.CatalogRepositoryImpl::class")
        assertEquals(1, LayerScanner.domainDependsOnNonCore(listOf(body)).size)
    }

    @Test fun scanner_catches_jakarta_coroutines_android_and_main_dispatcher() {
        val s = kt(
            "domain/x/D.kt",
            "import jakarta.inject.Inject\nimport kotlinx.coroutines.android.HandlerDispatcher\nimport kotlinx.coroutines.Dispatchers\n" +
                "val a = Dispatchers.Main\nval b = kotlinx.coroutines.Dispatchers\n    .Main.immediate",
        )
        assertEquals(2, LayerScanner.coreOrDomainUsesPlatform(listOf(s)).size)
        assertEquals(2, LayerScanner.coreOrDomainUsesMainDispatcher(listOf(s)).size)
        val byImport = kt("core/x/C.kt", "import kotlinx.coroutines.Dispatchers.Main\nval m = Main")
        assertEquals(1, LayerScanner.coreOrDomainUsesMainDispatcher(listOf(byImport)).size)
        // 주석·문자열, Default·IO, presentation은 대상 아님
        val ok = kt("domain/x/Ok.kt", "// Dispatchers.Main\nval s = \"Dispatchers.Main\"\nval d = kotlinx.coroutines.Dispatchers.Default")
        val pres = kt("presentation/x/P.kt", "import kotlinx.coroutines.android.HandlerDispatcher\nval m = kotlinx.coroutines.Dispatchers.Main")
        assertTrue(LayerScanner.coreOrDomainUsesMainDispatcher(listOf(ok, pres)).isEmpty())
        assertTrue(LayerScanner.coreOrDomainUsesPlatform(listOf(ok, pres)).isEmpty())
    }

    @Test fun scanner_catches_variant_only_type_from_main_and_allows_both_variants() {
        val debugSim = src("domain/source/VehicleSimulator.kt", "package $ROOT.domain.source\ninterface VehicleSimulator\nenum class SourceMode { A }", set = "debug")
        val debugPanel = src("presentation/debug/DebugPanel.kt", "package $ROOT.presentation.debug\n@Composable\nfun DebugPanel(m: Int) {}\nprivate fun helper() {}", set = "debug")
        val releasePanel = src("presentation/debug/DebugPanel.kt", "package $ROOT.presentation.debug\n@Composable fun DebugPanel(m: Int) {}", set = "release")
        val debugModule = src("di/VariantModule.kt", "package $ROOT.di\n@Module\nobject VariantModule", set = "debug")
        val releaseModule = src("di/VariantModule.kt", "package $ROOT.di\n@Module\nobject VariantModule", set = "release")
        val variants = listOf(debugSim, debugPanel, releasePanel, debugModule, releaseModule)
        assertEquals(
            setOf("$ROOT.domain.source.VehicleSimulator", "$ROOT.domain.source.SourceMode"),
            LayerScanner.variantOnlyNames(variants),
        )
        val byImport = kt("presentation/x/A.kt", "import $ROOT.domain.source.VehicleSimulator")
        val byFq = kt("di/B.kt", "val k = $ROOT.domain.source.SourceMode.A")
        val samePackage = kt("domain/source/C.kt", "class C(val s: VehicleSimulator)")
        val byWildcard = kt("data/x/D.kt", "import $ROOT.domain.source.*\nval m: SourceMode? = null")
        val both = kt("presentation/x/E.kt", "import $ROOT.presentation.debug.DebugPanel\nimport $ROOT.di.VariantModule\nval f = DebugPanel(1)")
        val debugUse = src("presentation/x/F.kt", "package $ROOT.presentation.x\nimport $ROOT.domain.source.VehicleSimulator", set = "debug")
        listOf(byImport, byFq, samePackage, byWildcard).forEach {
            assertEquals(it.path, 1, LayerScanner.mainUsesVariantOnlyType(variants + it).size)
        }
        assertTrue(LayerScanner.mainUsesVariantOnlyType(variants + both + debugUse).isEmpty())
        // release에 없는 debug 전용 선언은 release 디렉터리가 없어도(빌드 타입은 언제나 있다) 변형 전용이다
        assertEquals(setOf("$ROOT.domain.source.VehicleSimulator", "$ROOT.domain.source.SourceMode"), LayerScanner.variantOnlyNames(listOf(debugSim)))
    }

    @Test fun scanner_checks_naming_rules() {
        val repo = kt("domain/repository/FooRepository.kt", "interface FooRepository { fun x() }")
        val useCases = listOf(
            kt("domain/usecase/GoodUseCase.kt", "sealed interface Report { data class A(val x: Int) : Report }\nclass GoodUseCase"),
            kt("domain/usecase/Bad.kt", "class DoSomething(private val s: Int)"),
        )
        assertEquals(1, LayerScanner.useCaseNaming(useCases).size)

        val impls = listOf(
            repo,
            kt("data/foo/FooRepositoryImpl.kt", "class FooRepositoryImpl(\n    private val a: Int = 1,\n) : FooRepository {\n}"),
            kt("data/foo/FooStore.kt", "internal class FooStore private constructor(val a: () -> Unit = {}) :\n    Any(),\n    FooRepository"),
            kt("data/foo/Other.kt", "class Other : Comparable<Other> { override fun compareTo(other: Other) = 0 }\nobject FooObj : FooRepository"),
            kt("data/foo/User.kt", "class User(val r: FooRepository)"),
        )
        val found = LayerScanner.repositoryImplNaming(impls)
        assertEquals(found.toString(), 2, found.size)

        val mvi = listOf(
            kt("presentation/x/GoodAction.kt", "sealed interface GoodAction {\n    data object A : GoodAction\n}"),
            kt("presentation/x/BadAction.kt", "sealed class BadAction {\n    object A : BadAction()\n}"),
            kt("presentation/x/BadEvent.kt", "interface BadEvent"),
            kt("presentation/x/GoodUiState.kt", "data class GoodUiState(val a: Int = 0)\ndata class Other(val b: Int)"),
            kt("presentation/x/BadUiState.kt", "class BadUiState(val a: Int)"),
        )
        assertEquals(2, LayerScanner.actionAndEventShape(mvi).size)
        assertEquals(1, LayerScanner.uiStateShape(mvi).size)
    }
}
