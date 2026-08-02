package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SubagentManager

/**
 * 子代理管理工具：让主 agent 可以自己列出 / 读取 / 创建 / 更新 / 删除子代理角色定义（AGENT.md），
 * 与 skills 类似——用户直接在对话里说"帮我创建一个 XX 子代理"即可。
 */
fun createSubagentManagementTools(
    subagentManager: SubagentManager,
): List<Tool> = listOf(
    Tool(
        name = "list_subagents",
        description = """
            List all installed subagent roles (name, description, tools whitelist, model).
            Use this when the user asks what subagents are available, or before delegating/editing.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
            )
        },
        execute = {
            val list = subagentManager.listSubagents()
            val text = if (list.isEmpty()) {
                "No subagents installed. Use `create_subagent` to create one."
            } else {
                buildString {
                    appendLine("Installed subagents (${list.size}):")
                    list.forEach { s ->
                        appendLine("- ${s.name}: ${s.description}")
                        appendLine("    tools: ${s.tools.joinToString(", ").ifEmpty { "(all)" }}")
                        appendLine("    model: ${s.model ?: "(inherit main agent)"}")
                    }
                }
            }
            listOf(UIMessagePart.Text(text))
        }
    ),
    Tool(
        name = "read_subagent",
        description = """
            Read the full AGENT.md definition of an existing subagent role (frontmatter + body).
            Use this when the user asks to view a subagent's current definition, or before updating one.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Name of the subagent role to read")
                    })
                },
                required = listOf("name"),
            )
        },
        execute = { input ->
            val name = input.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(name.isNotBlank()) { "Missing required argument: name" }
            val content = subagentManager.readSubagentContent(name)
                ?: error("Subagent '$name' not found. Use `list_subagents` to see available roles.")
            listOf(UIMessagePart.Text(content))
        }
    ),
    Tool(
        name = "create_subagent",
        description = """
            Create or update a subagent role definition (AGENT.md).
            Subagents are reusable roles that can be delegated focused tasks via the `subagent` tool.
            Use this when the user asks to create, define or modify a subagent role, e.g.
            "create a code reviewer subagent" or "add a research subagent".
            The role body becomes the subagent's system prompt; tools is a whitelist the subagent may use.
            model supports 'provider/modelId' format, e.g. OpenAI/gpt-4o.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Unique role name (lowercase, no slashes)")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Short description of what this role does")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "The role's system prompt body: instructions, constraints and output contract")
                    })
                    put("tools", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional comma-separated tool whitelist, e.g. workspace_read_file, workspace_shell, search. " +
                                "Omit to allow all tools."
                        )
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional model for this role, e.g. 'gpt-4o' or 'OpenAI/gpt-4o'. Omit to inherit the main agent's model."
                        )
                    })
                    put("maxIterations", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max agent-loop iterations (default 10)")
                    })
                    put("temperature", buildJsonObject {
                        put("type", "number")
                        put("description", "Sampling temperature (default 0.2)")
                    })
                },
                required = listOf("name", "description", "prompt"),
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val name = args["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(name.isNotBlank()) { "Missing required argument: name" }
            val description = args["description"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(description.isNotBlank()) { "Missing required argument: description" }
            val prompt = args["prompt"]?.jsonPrimitive?.content.orEmpty()
            require(prompt.isNotBlank()) { "Missing required argument: prompt" }
            val tools = args["tools"]?.jsonPrimitive?.content?.trim()
            val model = args["model"]?.jsonPrimitive?.content?.trim()
            val maxIterations = args["maxIterations"]?.jsonPrimitive?.content?.toIntOrNull()
            val temperature = args["temperature"]?.jsonPrimitive?.content?.toFloatOrNull()

            val content = buildString {
                appendLine("---")
                appendLine("name: $name")
                appendLine("description: \"${description.replace("\"", "\\\"")}\"")
                if (!tools.isNullOrBlank()) appendLine("tools: $tools")
                if (!model.isNullOrBlank()) appendLine("model: $model")
                if (maxIterations != null) appendLine("maxIterations: $maxIterations")
                if (temperature != null) appendLine("temperature: $temperature")
                appendLine("---")
                appendLine()
                append(prompt.trim())
            }

            val saved = subagentManager.saveSubagent(name, content)
            require(saved != null) { "Failed to save subagent '$name'" }
            listOf(
                UIMessagePart.Text(
                    "Subagent '$name' saved successfully. " +
                        "Tools: ${saved.tools.joinToString().ifEmpty { "(all)" }}. " +
                        "Model: ${saved.model ?: "(inherit main agent)"}. " +
                        "Max iterations: ${saved.maxIterations}. " +
                        "It is now available via the `subagent` tool."
                )
            )
        }
    ),
    Tool(
        name = "delete_subagent",
        description = """
            Delete an existing subagent role definition by name.
            Use when the user asks to remove a subagent role.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Name of the subagent role to delete")
                    })
                },
                required = listOf("name"),
            )
        },
        execute = { input ->
            val name = input.jsonObject["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(name.isNotBlank()) { "Missing required argument: name" }
            val deleted = subagentManager.deleteSubagent(name)
            require(deleted) { "Failed to delete subagent '$name' (not found?)" }
            listOf(UIMessagePart.Text("Subagent '$name' deleted."))
        }
    ),
)
