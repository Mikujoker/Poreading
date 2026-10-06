package io.legado.app.ui.association

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.os.postDelayed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.canRead
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.MutableStateFlow
import splitties.init.appCtx

class FileAssociationActivity : BaseComposeActivity(transparent = true) {

    private val viewModel by viewModels<FileAssociationViewModel>()

    private val loadingFlow = MutableStateFlow(true)

    private val handler by lazy {
        buildMainHandler()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.importBookLiveData.observe(this) { uri ->
            importBook(uri)
        }
        viewModel.onLineImportLive.observe(this) {
            startActivity<OnLineImportActivity> {
                data = it
            }
            finish()
        }
        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> {
                    startActivity(MainActivity.createBookSourceManageIntent(this, it.second))
                    finish()
                }
                "rssSource" -> showDialogFragment(ImportRssSourceDialog(it.second, true))
                "replaceRule" -> showDialogFragment(ImportReplaceRuleDialog(it.second, true))
                "httpTts" -> showDialogFragment(ImportHttpTtsDialog(it.second, true))
                "theme" -> showDialogFragment(ImportThemeDialog(it.second, true))
                "txtRule" -> showDialogFragment(ImportTxtTocRuleDialog(it.second, true))
                "dictRule" -> showDialogFragment(ImportDictRuleDialog(it.second, true))
            }
        }
        viewModel.errorLive.observe(this) {
            loadingFlow.value = false
            toastOnUi(it)
            handler.postDelayed(2000) {
                finish()
            }
        }
        viewModel.openBookLiveData.observe(this) {
            loadingFlow.value = false
            startActivityForBook(it)
            finish()
        }
        viewModel.notSupportedLiveData.observe(this) { data ->
            loadingFlow.value = false
            alert(
                title = appCtx.getString(R.string.draw),
                message = appCtx.getString(R.string.file_not_supported, data.second)
            ) {
                yesButton {
                    importBook(data.first)
                }
                noButton {
                    finish()
                }
                onCancelled {
                    finish()
                }
            }
        }
        intent.data?.let { data ->
            if (data.isContentScheme() && data.canRead()) {
                viewModel.dispatchIntent(data)
            } else {
                PermissionsCompat.Builder()
                    .addPermissions(*Permissions.Group.STORAGE)
                    .rationale(R.string.tip_perm_request_storage)
                    .onGranted {
                        viewModel.dispatchIntent(data)
                    }.onDenied {
                        toastOnUi("请求存储权限失败。")
                        handler.postDelayed(2000) {
                            finish()
                        }
                    }.request()
            }
        } ?: finish()
    }

    @Composable
    override fun Content() {
        val loading by loadingFlow.collectAsStateWithLifecycle()
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppCircularProgressIndicator()
            }
        }
    }

    /**
     * 导入本地书文件。
     *
     * 文件落地由 [io.legado.app.model.localBook.LocalBook.importFile] 统一处理：搬到书库目录
     * （Download/legado/novel）并按书名改名，源文件随之移走。以前这里会往「默认书目录」拷一份副本，
     * 结果源文件原地不动、书库里多出一份，改名也只改到副本。
     */
    private fun importBook(uri: Uri) {
        viewModel.importBook(uri)
    }

}
