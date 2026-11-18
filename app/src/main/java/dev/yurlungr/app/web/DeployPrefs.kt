package dev.yurlungr.app.web

import android.content.Context
import androidx.core.content.edit
import dev.yurlungr.app.remote.SecretBox
import java.io.File

/**
 * Deploy settings, all in app-private preferences (never in the project, where agents and git would see them):
 * tokens and IDs per provider, sealed with [SecretBox]; the output folder and site name per project.
 * Call off the main thread: opening a sealed value goes through Android Keystore.
 */
internal object DeployPrefs {
    private fun secrets(context: Context) = context.getSharedPreferences("deploy_secrets", Context.MODE_PRIVATE)
    private fun projects(context: Context) = context.getSharedPreferences("deploy_projects", Context.MODE_PRIVATE)
    private fun key(provider: DeployProvider, env: String) = "${provider.name}.$env"
    private fun projectKey(root: File, what: String) = "${runCatching { root.canonicalPath }.getOrDefault(root.path)}|$what"

    fun secret(context: Context, provider: DeployProvider, env: String): String? =
        SecretBox.app.readPref(secrets(context), key(provider, env))?.takeIf { it.isNotEmpty() }

    fun values(context: Context, provider: DeployProvider): Map<String, String> =
        provider.keys.mapNotNull { k -> secret(context, provider, k.env)?.let { k.env to it } }.toMap()

    fun setSecret(context: Context, provider: DeployProvider, env: String, value: String) =
        SecretBox.app.writePref(secrets(context), key(provider, env), value.trim())

    /** Forgets everything saved for [provider]. */
    fun forget(context: Context, provider: DeployProvider) = secrets(context).edit {
        provider.keys.forEach { remove(key(provider, it.env)) }
    }

    fun output(context: Context, root: File): String? = projects(context).getString(projectKey(root, "out"), null)
    fun setOutput(context: Context, root: File, relative: String?) = projects(context).edit {
        if (relative.isNullOrBlank()) remove(projectKey(root, "out")) else putString(projectKey(root, "out"), relative)
    }

    fun site(context: Context, root: File, provider: DeployProvider): String = projects(context).getString(projectKey(root, "site.${provider.name}"), null).orEmpty()
    fun setSite(context: Context, root: File, provider: DeployProvider, site: String) =
        projects(context).edit { putString(projectKey(root, "site.${provider.name}"), site.trim()) }

    fun lastProvider(context: Context, root: File): DeployProvider? =
        projects(context).getString(projectKey(root, "provider"), null)?.let { n -> DeployProvider.entries.firstOrNull { it.name == n } }
    fun setLastProvider(context: Context, root: File, provider: DeployProvider) =
        projects(context).edit { putString(projectKey(root, "provider"), provider.name) }
}
