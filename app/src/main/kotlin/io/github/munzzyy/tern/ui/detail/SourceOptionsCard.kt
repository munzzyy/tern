package io.github.munzzyy.tern.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.munzzyy.tern.R
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonObject
import io.github.munzzyy.tern.core.json.JsonString
import io.github.munzzyy.tern.core.json.JsonValue
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.model.SourceSpec
import io.github.munzzyy.tern.core.source.SourceOptions
import io.github.munzzyy.tern.core.source.SourceTypes
import io.github.munzzyy.tern.core.source.web.PseudoVersion
import io.github.munzzyy.tern.core.source.web.RequestHeaders
import io.github.munzzyy.tern.ui.common.ChoiceRow
import io.github.munzzyy.tern.ui.common.QuietButton
import io.github.munzzyy.tern.ui.common.SwitchRow
import io.github.munzzyy.tern.ui.common.TonalButton
import io.github.munzzyy.tern.ui.common.rememberActions
import io.github.munzzyy.tern.ui.common.textFieldKeys
import io.github.munzzyy.tern.ui.theme.LocalLook
import io.github.munzzyy.tern.ui.theme.fingerprint

/** The sources that have options a person may set. */
val SOURCES_WITH_OPTIONS = setOf(SourceTypes.GITHUB, SourceTypes.FORGEJO, SourceTypes.GITHUB_ACTIONS, SourceTypes.HTML, SourceTypes.DIRECT, SourceTypes.SAMSUNG)

private val WORKFLOW_NAME = Regex("^[A-Za-z0-9._-]{1,100}\\.ya?ml$")
private val BRANCH_NAME = Regex("^[A-Za-z0-9._/-]{1,100}$")
private val DEVICE_MODEL = Regex("^[A-Za-z0-9-]{2,40}$")
private val CSC = Regex("^[A-Za-z0-9]{3}$")

/** The text options of a source, edited together and saved with one button. */
data class OptionsDraft(
    val workflow: String = "",
    val branch: String = "",
    val deviceModel: String = "",
    val csc: String = "",
    val linkFilter: String = "",
    val steps: String = "",
    val headers: String = "",
) {
    /** The fields that cannot be used as they are. */
    fun invalid(type: String): Set<String> = buildSet {
        if (type == SourceTypes.GITHUB_ACTIONS) {
            if (workflow.isNotBlank() && !WORKFLOW_NAME.matches(workflow.trim())) add("workflow")
            if (branch.isNotBlank() && !BRANCH_NAME.matches(branch.trim())) add("branch")
        }
        if (type == SourceTypes.SAMSUNG) {
            if (deviceModel.isNotBlank() && !DEVICE_MODEL.matches(deviceModel.trim())) add("deviceModel")
            if (csc.isNotBlank() && !CSC.matches(csc.trim())) add("csc")
        }
        if (type == SourceTypes.HTML) {
            if (!isValidPattern(linkFilter)) add("linkFilter")
            if (stepLines(steps).any { !isValidPattern(it) }) add("steps")
        }
        if ((type == SourceTypes.HTML || type == SourceTypes.DIRECT) && headerMap(headers) == null) add("headers")
    }

    /** [spec] with these options, the others it has kept as they were. Steps that were objects keep their flags when their pattern stays. */
    fun applyTo(spec: SourceSpec): SourceSpec {
        val options = LinkedHashMap(spec.options)
        fun put(key: String, value: String?) {
            if (value.isNullOrBlank()) options.remove(key) else options[key] = value
        }
        when (spec.type) {
            SourceTypes.GITHUB_ACTIONS -> {
                put(SourceOptions.WORKFLOW, workflow.trim())
                put(SourceOptions.BRANCH, branch.trim())
            }
            SourceTypes.SAMSUNG -> {
                put(SourceOptions.DEVICE_MODEL, deviceModel.trim())
                put(SourceOptions.CSC, csc.trim().uppercase())
            }
            SourceTypes.HTML -> {
                put(SourceOptions.LINK_FILTER, linkFilter.trim())
                val before = storedSteps(spec).associateBy { stepFilter(it) }
                val lines = stepLines(steps)
                put(SourceOptions.STEPS, if (lines.isEmpty()) null else Json.write(Json.of(lines.map { before[it] ?: JsonString(it) })))
                put(SourceOptions.HEADERS, headerMap(headers)?.takeIf { it.isNotEmpty() }?.let(RequestHeaders::write))
            }
            SourceTypes.DIRECT -> put(SourceOptions.HEADERS, headerMap(headers)?.takeIf { it.isNotEmpty() }?.let(RequestHeaders::write))
        }
        return spec.copy(options = options)
    }

    companion object {
        fun of(spec: SourceSpec) = OptionsDraft(
            workflow = spec.option(SourceOptions.WORKFLOW).orEmpty(),
            branch = spec.option(SourceOptions.BRANCH).orEmpty(),
            deviceModel = spec.option(SourceOptions.DEVICE_MODEL).orEmpty(),
            csc = spec.option(SourceOptions.CSC).orEmpty(),
            linkFilter = spec.option(SourceOptions.LINK_FILTER).orEmpty(),
            steps = storedSteps(spec).mapNotNull(::stepFilter).joinToString("\n"),
            headers = runCatching { RequestHeaders.parse(spec.option(SourceOptions.HEADERS)) }.getOrDefault(emptyMap())
                .entries.joinToString("\n") { "${it.key}: ${it.value}" },
        )

        private fun storedSteps(spec: SourceSpec): List<JsonValue> =
            spec.option(SourceOptions.STEPS)?.let { raw -> runCatching { Json.parseArray(raw).items }.getOrNull() }.orEmpty()

        private fun stepFilter(step: JsonValue): String? = when (step) {
            is JsonString -> step.value
            is JsonObject -> step.string("filter")
            else -> null
        }

        private fun stepLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_STEPS)

        /** The headers the lines name, or null when one of them may not be sent. */
        fun headerMap(text: String): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                val name = line.substringBefore(':', "").trim()
                val value = line.substringAfter(':', "").trim()
                if (name.isEmpty() || RequestHeaders.problem(name, value) != null) return null
                if (out.keys.any { it.equals(name, ignoreCase = true) }) return null
                out[name] = value
            }
            return out.takeIf { it.size <= RequestHeaders.MAX_HEADERS }
        }

        private const val MAX_STEPS = 8
    }
}

/**
 * What Obtainium calls the additional options of a source: for GitHub and Forgejo which release
 * is latest and how releases are dated, the workflow of GitHub Actions, the device the Galaxy Store
 * is asked for, and how a web page or a direct link is read.
 */
@Composable
fun SourceOptionsCard(vm: DetailViewModel, config: AppConfig) {
    val spec = config.source
    if (spec.type !in SOURCES_WITH_OPTIONS) return
    val look = LocalLook.current
    val actions = rememberActions()
    val failed = stringResource(R.string.save_failed)
    val save: ((SourceSpec) -> SourceSpec) -> Unit = { change -> vm.save({ it.copy(source = change(it.source)) }) { actions.say(failed) } }
    val flag = { key: String -> spec.option(key) == "true" }
    val setFlag = { key: String, on: Boolean ->
        save { s -> s.copy(options = if (on) s.options + (key to "true") else s.options - key) }
    }
    var draft by rememberSaveable(config.id, spec) { mutableStateOf(OptionsDraft.of(spec)) }
    val invalid = draft.invalid(spec.type)
    val dirty = draft != OptionsDraft.of(spec)
    DetailCard(stringResource(R.string.group_source_options)) {
        when (spec.type) {
            SourceTypes.GITHUB, SourceTypes.FORGEJO -> {
                SwitchRow(
                    title = stringResource(R.string.option_verify_latest),
                    summary = stringResource(R.string.option_verify_latest_effect),
                    checked = flag(SourceOptions.VERIFY_LATEST),
                    onChange = { setFlag(SourceOptions.VERIFY_LATEST, it) },
                )
                SwitchRow(
                    title = stringResource(R.string.option_asset_date),
                    summary = stringResource(R.string.option_asset_date_effect),
                    checked = flag(SourceOptions.ASSET_DATE),
                    onChange = { setFlag(SourceOptions.ASSET_DATE, it) },
                )
            }
            SourceTypes.HTML -> {
                SwitchRow(
                    title = stringResource(R.string.option_page_order),
                    summary = stringResource(R.string.option_page_order_effect),
                    checked = spec.option(SourceOptions.SORT) == "page",
                    onChange = { on -> save { s -> s.copy(options = if (on) s.options + (SourceOptions.SORT to "page") else s.options - SourceOptions.SORT) } },
                )
                SwitchRow(stringResource(R.string.option_first_link), flag(SourceOptions.FIRST_LINK), { setFlag(SourceOptions.FIRST_LINK, it) })
                SwitchRow(stringResource(R.string.option_last_segment), flag(SourceOptions.LAST_SEGMENT), { setFlag(SourceOptions.LAST_SEGMENT, it) })
                SwitchRow(
                    title = stringResource(R.string.option_any_text),
                    summary = stringResource(R.string.option_any_text_effect),
                    checked = flag(SourceOptions.ANY_TEXT),
                    onChange = { setFlag(SourceOptions.ANY_TEXT, it) },
                )
                ChoiceRow(
                    title = stringResource(R.string.option_version_from),
                    options = listOf("link", "text", "page"),
                    selected = spec.option(SourceOptions.VERSION_FROM) ?: "link",
                    label = {
                        stringResource(
                            when (it) {
                                "text" -> R.string.version_from_text
                                "page" -> R.string.version_from_page
                                else -> R.string.version_from_link
                            },
                        )
                    },
                    onSelect = { from -> save { s -> s.copy(options = if (from == "link") s.options - SourceOptions.VERSION_FROM else s.options + (SourceOptions.VERSION_FROM to from)) } },
                )
            }
        }
        if (spec.type == SourceTypes.HTML || spec.type == SourceTypes.DIRECT) {
            val current = spec.option(SourceOptions.PSEUDO)
            ChoiceRow(
                title = stringResource(R.string.option_pseudo),
                options = listOf("") + PseudoVersion.entries.map { it.option },
                selected = current.orEmpty(),
                label = {
                    stringResource(
                        when (it) {
                            PseudoVersion.HASH.option -> R.string.pseudo_hash
                            PseudoVersion.LINK.option -> R.string.pseudo_link
                            PseudoVersion.ETAG.option -> R.string.pseudo_etag
                            else -> R.string.pseudo_default
                        },
                    )
                },
                onSelect = { choice -> save { s -> s.copy(options = if (choice.isEmpty()) s.options - SourceOptions.PSEUDO else s.options + (SourceOptions.PSEUDO to choice)) } },
            )
        }
        val fields = fieldsFor(spec.type)
        if (fields.isEmpty()) return@DetailCard
        Column(
            verticalArrangement = Arrangement.spacedBy(look.gapSmall + look.gapSmall / 2),
            modifier = Modifier.fillMaxWidth().padding(horizontal = look.cardPadding, vertical = look.gapSmall / 2),
        ) {
            for (field in fields) {
                OptionField(field, draft, "${field.key}" in invalid) { draft = it }
            }
            if (dirty) {
                Row(horizontalArrangement = Arrangement.spacedBy(look.focusRoom * 2, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                    QuietButton(stringResource(R.string.action_discard), onClick = { draft = OptionsDraft.of(spec) })
                    TonalButton(stringResource(R.string.action_save), onClick = { save { draft.applyTo(it) } }, enabled = invalid.isEmpty())
                }
            }
        }
    }
}

private enum class OptionField(val key: String, val label: Int, val help: Int?, val lines: Boolean = false, val code: Boolean = false) {
    WORKFLOW("workflow", R.string.option_workflow, R.string.option_workflow_help),
    BRANCH("branch", R.string.option_branch, null),
    DEVICE_MODEL("deviceModel", R.string.option_device_model, R.string.option_device_model_help),
    CSC("csc", R.string.option_csc, R.string.option_csc_help),
    LINK_FILTER("linkFilter", R.string.option_link_filter, R.string.option_link_filter_help, code = true),
    STEPS("steps", R.string.option_steps, R.string.option_steps_help, lines = true, code = true),
    HEADERS("headers", R.string.option_headers, R.string.option_headers_help, lines = true, code = true),
}

private fun fieldsFor(type: String): List<OptionField> = when (type) {
    SourceTypes.GITHUB_ACTIONS -> listOf(OptionField.WORKFLOW, OptionField.BRANCH)
    SourceTypes.SAMSUNG -> listOf(OptionField.DEVICE_MODEL, OptionField.CSC)
    SourceTypes.HTML -> listOf(OptionField.LINK_FILTER, OptionField.STEPS, OptionField.HEADERS)
    SourceTypes.DIRECT -> listOf(OptionField.HEADERS)
    else -> emptyList()
}

@Composable
private fun OptionField(field: OptionField, draft: OptionsDraft, invalid: Boolean, onChange: (OptionsDraft) -> Unit) {
    val value = when (field) {
        OptionField.WORKFLOW -> draft.workflow
        OptionField.BRANCH -> draft.branch
        OptionField.DEVICE_MODEL -> draft.deviceModel
        OptionField.CSC -> draft.csc
        OptionField.LINK_FILTER -> draft.linkFilter
        OptionField.STEPS -> draft.steps
        OptionField.HEADERS -> draft.headers
    }
    val change: (String) -> OptionsDraft = { v ->
        val text = v.take(if (field.lines) 2000 else 500)
        when (field) {
            OptionField.WORKFLOW -> draft.copy(workflow = text)
            OptionField.BRANCH -> draft.copy(branch = text)
            OptionField.DEVICE_MODEL -> draft.copy(deviceModel = text)
            OptionField.CSC -> draft.copy(csc = text)
            OptionField.LINK_FILTER -> draft.copy(linkFilter = text)
            OptionField.STEPS -> draft.copy(steps = text)
            OptionField.HEADERS -> draft.copy(headers = text)
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(change(it)) },
        label = { Text(stringResource(field.label)) },
        supportingText = when {
            invalid -> {
                { Text(stringResource(R.string.option_invalid)) }
            }
            field.help != null -> {
                { Text(stringResource(field.help)) }
            }
            else -> null
        },
        isError = invalid,
        singleLine = !field.lines,
        minLines = if (field.lines) 2 else 1,
        textStyle = if (field.code) MaterialTheme.typography.bodyLarge.fingerprint() else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().textFieldKeys(),
    )
}
