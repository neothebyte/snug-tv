package tv.livingroom.watch

import android.app.Application
import coil.Coil
import coil.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LivingRoomApp : Application() {
    lateinit var store: SessionStore
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var api: JellyfinClient
        private set

    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val codecs: DeviceCodecs by lazy { DeviceCodecs.detect(this) }

    override fun onCreate() {
        super.onCreate()
        store = SessionStore(this)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = store.token
                val request = if (token.isNullOrBlank()) {
                    chain.request()
                } else {
                    chain.request().newBuilder().header("X-Emby-Token", token).build()
                }
                chain.proceed(request)
            }
            .build()
        api = JellyfinClient(store, http) { codecs }
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .okHttpClient(http)
                // No fade by default: fading every poster in while a grid scrolls costs frames on
                // weak TV boxes. The big backdrop still fades (see FullBackdrop).
                .crossfade(false)
                .build()
        )
    }
}

sealed interface Screen {
    val key: String

    data object Login : Screen { override val key = "login" }
    data object Home : Screen { override val key = "home" }
    data object Search : Screen { override val key = "search" }
    data object Movies : Screen { override val key = "movies" }
    data object Shows : Screen { override val key = "shows" }
    /** A show's page. [episodeId] is the episode to land on, when it was opened from one. */
    data class Series(val id: String, val episodeId: String? = null) : Screen { override val key = "series:$id" }
    data object Genres : Screen { override val key = "genres" }
    data class Genre(val name: String) : Screen { override val key = "genre:$name" }
    data class Detail(val id: String) : Screen { override val key = "detail:$id" }
    data class Person(val name: String, val role: String) : Screen { override val key = "person:$name:$role" }
    data object Settings : Screen { override val key = "settings" }
}
