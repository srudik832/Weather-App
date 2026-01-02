package com.example.weather

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*

// =========================================================
// STYLING
// =========================================================
val GlassWhite = Color(0x26FFFFFF)
val GlassBorder = Color(0x33FFFFFF)
val DarkBackground = Color(0xFF0F0F0F)

// =========================================================
// DATABASE (Offline Storage)
// =========================================================
@androidx.room.Entity(tableName = "weather_cache")
data class WeatherEntity(
    @androidx.room.PrimaryKey val cityName: String,
    val temperature: Double,
    val isDay: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@androidx.room.Dao
interface WeatherDao {
    @androidx.room.Query("SELECT * FROM weather_cache ORDER BY timestamp DESC")
    suspend fun getAllSaved(): List<WeatherEntity>
    @androidx.room.Query("SELECT * FROM weather_cache ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): WeatherEntity?
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun saveWeather(weather: WeatherEntity)
    @androidx.room.Delete
    suspend fun deleteLocation(weather: WeatherEntity)
}

@androidx.room.Database(entities = [WeatherEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : androidx.room.RoomDatabase() {
    abstract fun weatherDao(): WeatherDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            val instance = androidx.room.Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "weather_db")
                .fallbackToDestructiveMigration().build()
            INSTANCE = instance
            instance
        }
    }
}

// =========================================================
// API & MODELS
// =========================================================
data class WeatherResponse(
    val current_weather: CurrentWeather,
    val hourly: HourlyData?,
    val daily: DailyData?,
    val utc_offset_seconds: Int // Used for real local time
)
data class CurrentWeather(val temperature: Double, val weathercode: Int, val is_day: Int)
data class HourlyData(val time: List<String>, val temperature_2m: List<Double>)
data class DailyData(val time: List<String>, val temperature_2m_max: List<Double>, val temperature_2m_min: List<Double>)

interface WeatherApi {
    @GET("v1/forecast?current_weather=true&hourly=temperature_2m&daily=temperature_2m_max,temperature_2m_min&timezone=auto")
    suspend fun getWeather(@Query("latitude") lat: Double, @Query("longitude") lon: Double): WeatherResponse
}

// =========================================================
// VIEWMODEL (Logic Hub)
// =========================================================
class WeatherViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getDatabase(app)
    private val api = Retrofit.Builder().baseUrl("https://api.open-meteo.com/").addConverterFactory(GsonConverterFactory.create()).build().create(WeatherApi::class.java)

    var cityName by mutableStateOf("Offline")
    var currentTemp by mutableStateOf(0.0)
    var isDayTime by mutableStateOf(1)
    var hourlyItems by mutableStateOf<List<Pair<String, Int>>>(emptyList())
    var dailyItems by mutableStateOf<List<Triple<String, Int, Int>>>(emptyList())
    var savedLocations by mutableStateOf<List<WeatherEntity>>(emptyList())
    var searchPredictions by mutableStateOf<List<Address>>(emptyList())

    var localTime by mutableStateOf("...")
    var isSearchOpen by mutableStateOf(false)
    var isManageOpen by mutableStateOf(false)
    var isLoading by mutableStateOf(false)

    private var searchJob: Job? = null

    init {
        loadLastKnown()
        refreshSavedList()
    }

    private fun loadLastKnown() {
        viewModelScope.launch {
            db.weatherDao().getLatest()?.let {
                cityName = it.cityName
                currentTemp = it.temperature
                isDayTime = it.isDay
            }
        }
    }

    fun refreshSavedList() {
        viewModelScope.launch { savedLocations = db.weatherDao().getAllSaved() }
    }

    private fun updateLocalTime(offsetSeconds: Int) {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.add(Calendar.SECOND, offsetSeconds)
        val sdf = SimpleDateFormat("EEE h a", Locale.getDefault())
        localTime = sdf.format(calendar.time)
    }

    fun onSearchQueryChanged(query: String, context: Context) {
        searchJob?.cancel()
        if (query.isBlank()) { searchPredictions = emptyList(); return }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            try { searchPredictions = Geocoder(context).getFromLocationName(query, 5) ?: emptyList() } catch (e: Exception) { }
        }
    }

    fun fetch(lat: Double, lon: Double, context: Context, name: String? = null) {
        viewModelScope.launch {
            isLoading = true
            try {
                val res = api.getWeather(lat, lon)
                cityName = name ?: Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull()?.locality ?: "Unknown"
                currentTemp = res.current_weather.temperature
                isDayTime = res.current_weather.is_day
                updateLocalTime(res.utc_offset_seconds)

                hourlyItems = res.hourly?.time?.take(24)?.mapIndexed { i, t -> t.substringAfter("T") to res.hourly.temperature_2m[i].toInt() } ?: emptyList()
                dailyItems = res.daily?.time?.mapIndexed { i, t -> Triple(if(i==0) "Today" else t.substring(5, 10), res.daily.temperature_2m_max[i].toInt(), res.daily.temperature_2m_min[i].toInt()) } ?: emptyList()

                db.weatherDao().saveWeather(WeatherEntity(cityName, currentTemp, isDayTime))
                refreshSavedList()
            } catch (e: Exception) {
                // Offline fallback already handled by loadLastKnown init
            } finally { isLoading = false; isSearchOpen = false; isManageOpen = false }
        }
    }

    fun deleteLocation(entity: WeatherEntity) {
        viewModelScope.launch { db.weatherDao().deleteLocation(entity); refreshSavedList() }
    }
}

// =========================================================
// MAIN UI
// =========================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherAppPro(vm: WeatherViewModel = viewModel()) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val topColor by animateColorAsState(if (vm.isDayTime == 1) Color(0xFF1D4ED8) else Color(0xFF0F172A), tween(1500), label = "top")
    val bottomColor by animateColorAsState(if (vm.isDayTime == 1) Color(0xFF60A5FA) else Color(0xFF020617), tween(1500), label = "bottom")

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.6f).fillMaxHeight(), drawerContainerColor = DarkBackground) {
                Column(Modifier.padding(24.dp)) {
                    Text("Menu", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(32.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                        vm.isManageOpen = true
                        scope.launch { drawerState.close() }
                    }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, null, tint = Color.White)
                        Spacer(Modifier.width(16.dp))
                        Text("Manage Locations", color = Color.White)
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Rounded.Segment, null, tint = Color.White) } },
                    title = { Text(vm.cityName, color = Color.White, fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { vm.isSearchOpen = true }) {
                            Box(Modifier.size(40.dp).background(GlassWhite, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                )
            }
        ) { p ->
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(topColor, bottomColor))).padding(p)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${vm.currentTemp.toInt()}°", fontSize = 110.sp, color = Color.White, fontWeight = FontWeight.Thin)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(color = GlassWhite, shape = RoundedCornerShape(12.dp)) {
                                    Text("Feels like ${vm.currentTemp.toInt() + 1}°", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp)
                                }
                                Surface(color = GlassWhite, shape = RoundedCornerShape(12.dp)) {
                                    Text(vm.localTime, color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    item {
                        Text("Next 24 Hours", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        Card(modifier = Modifier.fillMaxWidth().border(1.dp, GlassBorder, RoundedCornerShape(24.dp)), colors = CardDefaults.cardColors(containerColor = GlassWhite)) {
                            LazyRow(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                items(vm.hourlyItems) { h ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(h.first, color = Color.White.copy(0.6f), fontSize = 11.sp)
                                        Icon(Icons.Rounded.Cloud, null, tint = Color.White, modifier = Modifier.padding(vertical = 8.dp).size(20.dp))
                                        Text("${h.second}°", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text("7-Day Forecast", color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp, top = 24.dp))
                        Card(modifier = Modifier.fillMaxWidth().border(1.dp, GlassBorder, RoundedCornerShape(24.dp)), colors = CardDefaults.cardColors(containerColor = GlassWhite)) {
                            Column(Modifier.padding(20.dp)) {
                                vm.dailyItems.forEachIndexed { idx, d ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.first, color = Color.White, modifier = Modifier.weight(1f))
                                        Icon(Icons.Rounded.WbSunny, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Text("${d.second}° / ${d.third}°", color = Color.White, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                    }
                                    if (idx < vm.dailyItems.size - 1) HorizontalDivider(color = GlassBorder)
                                }
                            }
                        }
                    }
                }
                if (vm.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Color.White)
            }
        }
    }

    if (vm.isSearchOpen) SearchScreenPro(vm)
    if (vm.isManageOpen) ManageLocationsScreenPro(vm)

    LaunchedEffect(Unit) { vm.fetch(9.93, 76.26, context) } // Startup Fetch
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreenPro(vm: WeatherViewModel) {
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    BackHandler { vm.isSearchOpen = false }

    Box(Modifier.fillMaxSize().background(DarkBackground.copy(0.98f)).padding(20.dp)) {
        Column {
            Spacer(Modifier.height(40.dp))
            TextField(
                value = query,
                onValueChange = { query = it; vm.onSearchQueryChanged(it, context) },
                modifier = Modifier.fillMaxWidth().border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
                placeholder = { Text("Search location...", color = Color.Gray) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = GlassWhite,
                    unfocusedContainerColor = GlassWhite,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(20.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.White) }
            )

            Spacer(Modifier.height(30.dp))
            val isHistory = query.isEmpty()
            Text(if (isHistory) "Recent Searches" else "Suggestions", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isHistory) {
                    items(vm.savedLocations) { loc ->
                        Card(
                            modifier = Modifier.fillMaxWidth().border(0.5.dp, GlassBorder, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = GlassWhite),
                            onClick = {
                                val addr = Geocoder(context).getFromLocationName(loc.cityName, 1)
                                addr?.firstOrNull()?.let { vm.fetch(it.latitude, it.longitude, context, loc.cityName) }
                            }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.History, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(loc.cityName, color = Color.White)
                            }
                        }
                    }
                } else {
                    items(vm.searchPredictions) { addr ->
                        Column(Modifier.fillMaxWidth().clickable {
                            vm.fetch(addr.latitude, addr.longitude, context, addr.locality ?: addr.featureName)
                        }.padding(16.dp)) {
                            Text(addr.locality ?: addr.featureName ?: "Unknown", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${addr.adminArea ?: ""}, ${addr.countryName ?: ""}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ManageLocationsScreenPro(vm: WeatherViewModel) {
    val context = LocalContext.current
    BackHandler { vm.isManageOpen = false }

    Box(Modifier.fillMaxSize().background(DarkBackground).padding(24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.isManageOpen = false }) { Icon(Icons.Rounded.ArrowBackIosNew, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Text("Manage", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.savedLocations) { loc ->
                    Card(modifier = Modifier.fillMaxWidth().border(1.dp, GlassBorder, RoundedCornerShape(20.dp)), colors = CardDefaults.cardColors(containerColor = GlassWhite)) {
                        Row(Modifier.padding(20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable {
                                val addr = Geocoder(context).getFromLocationName(loc.cityName, 1)
                                addr?.firstOrNull()?.let { vm.fetch(it.latitude, it.longitude, context, loc.cityName) }
                            }) {
                                Text(loc.cityName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("${loc.temperature.toInt()}°", color = Color.White.copy(0.6f))
                            }
                            IconButton(onClick = { vm.deleteLocation(loc) }) { Icon(Icons.Rounded.Close, null, tint = Color.White.copy(0.4f)) }
                        }
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WeatherAppPro() }
    }
}