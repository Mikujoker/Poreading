package io.legado.app.ui.book.info.edit

import android.os.Bundle
import org.koin.androidx.viewmodel.ext.android.viewModel
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.main.MainActivity

class BookInfoEditActivity : BaseComposeActivity() {

    companion object {
        /** 连源文件改名后的新 bookUrl，详情页据此立刻重载（否则页面还挂在旧 URL 上）。 */
        const val EXTRA_NEW_BOOK_URL = "newBookUrl"
    }

    private val viewModel by viewModel<BookInfoEditViewModel>()

    @Composable
    override fun Content() {
        BookInfoEditScreen(
            viewModel = viewModel,
            onBack = { finish() },
            onSave = {
                viewModel.save(onSuccess = {
                    viewModel.renamedBookUrl?.let { intent.putExtra(EXTRA_NEW_BOOK_URL, it) }
                    setResult(RESULT_OK, intent)
                    finish()
                })
            },
            onOpenCharacterList = { bookUrl ->
                startActivity(MainActivity.createBookCharacterListIntent(this, bookUrl))
            },
            onOpenCharacterNetwork = { bookUrl ->
                startActivity(MainActivity.createBookCharacterNetworkIntent(this, bookUrl))
            },
            onOpenKnowledgeList = { bookUrl ->
                startActivity(MainActivity.createBookKnowledgeListIntent(this, bookUrl))
            },
            onOpenEventList = { bookUrl ->
                startActivity(MainActivity.createBookEventListIntent(this, bookUrl))
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("bookUrl")?.let {
            viewModel.loadBook(it)
        }
    }

}
