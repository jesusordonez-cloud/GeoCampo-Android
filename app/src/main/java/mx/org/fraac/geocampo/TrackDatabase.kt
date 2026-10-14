package mx.org.fraac.geocampo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TrackDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE folders(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent_id INTEGER,
                name TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE projects(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER,
                name TEXT NOT NULL,
                ejido TEXT NOT NULL DEFAULT '',
                municipality TEXT NOT NULL DEFAULT '',
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE tracks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER,
                name TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE track_points(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                track_id INTEGER NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                altitude REAL,
                accuracy REAL,
                ts INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_track_points_track ON track_points(track_id, ts)")
        db.execSQL(
            """CREATE TABLE waypoints(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER,
                title TEXT NOT NULL,
                note TEXT NOT NULL,
                category TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                altitude REAL,
                accuracy REAL,
                ts INTEGER NOT NULL,
                photo_path TEXT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_waypoints_project ON waypoints(project_id, ts)")
        db.execSQL(
            """CREATE TABLE waypoint_photos(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                waypoint_id INTEGER NOT NULL,
                path TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_waypoint_photos_waypoint ON waypoint_photos(waypoint_id, created_at)")
        db.execSQL("CREATE INDEX idx_tracks_project ON tracks(project_id, started_at)")
        db.execSQL("CREATE INDEX idx_projects_folder ON projects(folder_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS projects(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    ejido TEXT NOT NULL DEFAULT '',
                    municipality TEXT NOT NULL DEFAULT '',
                    notes TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )""".trimIndent()
            )
            runCatching { db.execSQL("ALTER TABLE tracks ADD COLUMN project_id INTEGER") }
            runCatching { db.execSQL("ALTER TABLE waypoints ADD COLUMN project_id INTEGER") }
            runCatching { db.execSQL("ALTER TABLE waypoints ADD COLUMN photo_path TEXT") }
            runCatching { db.execSQL("CREATE INDEX idx_waypoints_project ON waypoints(project_id, ts)") }
            runCatching { db.execSQL("CREATE INDEX idx_tracks_project ON tracks(project_id, started_at)") }
        }
        if (oldVersion < 3) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS folders(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    parent_id INTEGER,
                    name TEXT NOT NULL,
                    notes TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )""".trimIndent()
            )
            runCatching { db.execSQL("ALTER TABLE projects ADD COLUMN folder_id INTEGER") }
            runCatching { db.execSQL("CREATE INDEX idx_projects_folder ON projects(folder_id, created_at)") }
        }
        if (oldVersion < 4) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS waypoint_photos(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    waypoint_id INTEGER NOT NULL,
                    path TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )""".trimIndent()
            )
            runCatching {
                db.execSQL("CREATE INDEX idx_waypoint_photos_waypoint ON waypoint_photos(waypoint_id, created_at)")
            }
            runCatching {
                db.execSQL(
                    """INSERT INTO waypoint_photos(waypoint_id,path,created_at)
                       SELECT id,photo_path,ts FROM waypoints
                       WHERE photo_path IS NOT NULL AND TRIM(photo_path) <> ''
                       AND NOT EXISTS(
                           SELECT 1 FROM waypoint_photos wp
                           WHERE wp.waypoint_id=waypoints.id AND wp.path=waypoints.photo_path
                       )""".trimIndent()
                )
            }
        }
    }

    @Synchronized
    fun insertFolder(name: String, notes: String = "", parentId: Long? = null): Long =
        writableDatabase.insertOrThrow(
            "folders", null,
            ContentValues().apply {
                parentId?.let { put("parent_id", it) }
                put("name", name)
                put("notes", notes)
                put("created_at", System.currentTimeMillis())
            }
        )

    @Synchronized
    fun getFolders(): List<FieldFolder> {
        val out = mutableListOf<FieldFolder>()
        readableDatabase.query(
            "folders",
            arrayOf("id", "parent_id", "name", "notes", "created_at"),
            null, null, null, null, "name COLLATE NOCASE ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += FieldFolder(
                    id = c.getLong(0),
                    parentId = if (c.isNull(1)) null else c.getLong(1),
                    name = c.getString(2),
                    notes = c.getString(3),
                    createdAt = c.getLong(4)
                )
            }
        }
        return out
    }

    @Synchronized
    fun deleteFolder(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update(
                "projects",
                ContentValues().apply { putNull("folder_id") },
                "folder_id=?",
                arrayOf(id.toString())
            )
            writableDatabase.update(
                "folders",
                ContentValues().apply { putNull("parent_id") },
                "parent_id=?",
                arrayOf(id.toString())
            )
            writableDatabase.delete("folders", "id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun insertProject(
        name: String,
        ejido: String,
        municipality: String,
        notes: String,
        folderId: Long? = null
    ): Long =
        writableDatabase.insertOrThrow(
            "projects", null,
            ContentValues().apply {
                folderId?.let { put("folder_id", it) }
                put("name", name)
                put("ejido", ejido)
                put("municipality", municipality)
                put("notes", notes)
                put("created_at", System.currentTimeMillis())
            }
        )

    @Synchronized
    fun getProjects(): List<FieldProject> {
        val out = mutableListOf<FieldProject>()
        readableDatabase.query(
            "projects",
            arrayOf("id", "folder_id", "name", "ejido", "municipality", "notes", "created_at"),
            null, null, null, null, "created_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += FieldProject(
                    id = c.getLong(0),
                    folderId = if (c.isNull(1)) null else c.getLong(1),
                    name = c.getString(2),
                    ejido = c.getString(3),
                    municipality = c.getString(4),
                    notes = c.getString(5),
                    createdAt = c.getLong(6)
                )
            }
        }
        return out
    }

    @Synchronized
    fun deleteProject(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.update("tracks", ContentValues().apply { putNull("project_id") }, "project_id=?", arrayOf(id.toString()))
            writableDatabase.update("waypoints", ContentValues().apply { putNull("project_id") }, "project_id=?", arrayOf(id.toString()))
            writableDatabase.delete("projects", "id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun startTrack(name: String, startedAt: Long = System.currentTimeMillis(), projectId: Long? = null): Long =
        writableDatabase.insertOrThrow(
            "tracks", null,
            ContentValues().apply {
                projectId?.let { put("project_id", it) }
                put("name", name)
                put("started_at", startedAt)
            }
        )

    @Synchronized
    fun stopTrack(trackId: Long, endedAt: Long = System.currentTimeMillis()) {
        writableDatabase.update(
            "tracks",
            ContentValues().apply { put("ended_at", endedAt) },
            "id=?",
            arrayOf(trackId.toString())
        )
    }

    @Synchronized
    fun getTracks(projectId: Long? = null, filterByProject: Boolean = false): List<TrackSummary> {
        val out = mutableListOf<TrackSummary>()
        val where = if (filterByProject) {
            if (projectId == null) "t.project_id IS NULL" else "t.project_id=?"
        } else null
        val args = if (filterByProject && projectId != null) arrayOf(projectId.toString()) else null
        val sql = buildString {
            append("SELECT t.id,t.project_id,t.name,t.started_at,t.ended_at,COUNT(p.id) ")
            append("FROM tracks t LEFT JOIN track_points p ON p.track_id=t.id ")
            if (where != null) append("WHERE $where ")
            append("GROUP BY t.id,t.project_id,t.name,t.started_at,t.ended_at ORDER BY t.started_at DESC")
        }
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += TrackSummary(
                    id = c.getLong(0),
                    projectId = if (c.isNull(1)) null else c.getLong(1),
                    name = c.getString(2),
                    startedAt = c.getLong(3),
                    endedAt = if (c.isNull(4)) null else c.getLong(4),
                    pointCount = c.getInt(5)
                )
            }
        }
        return out
    }

    @Synchronized
    fun insertTrackPoint(point: TrackPoint): Long =
        writableDatabase.insertOrThrow(
            "track_points", null,
            ContentValues().apply {
                put("track_id", point.trackId)
                put("lat", point.lat)
                put("lon", point.lon)
                point.altitude?.let { put("altitude", it) }
                point.accuracy?.let { put("accuracy", it) }
                put("ts", point.timestamp)
            }
        )

    @Synchronized
    fun getTrackPoints(trackId: Long): List<TrackPoint> {
        val out = mutableListOf<TrackPoint>()
        readableDatabase.query(
            "track_points",
            arrayOf("id", "track_id", "lat", "lon", "altitude", "accuracy", "ts"),
            "track_id=?",
            arrayOf(trackId.toString()),
            null, null, "ts ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += TrackPoint(
                    id = c.getLong(0),
                    trackId = c.getLong(1),
                    lat = c.getDouble(2),
                    lon = c.getDouble(3),
                    altitude = if (c.isNull(4)) null else c.getDouble(4),
                    accuracy = if (c.isNull(5)) null else c.getFloat(5),
                    timestamp = c.getLong(6)
                )
            }
        }
        return out
    }

    @Synchronized
    fun deleteTrack(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("track_points", "track_id=?", arrayOf(id.toString()))
            writableDatabase.delete("tracks", "id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun insertWaypoint(
        projectId: Long?,
        title: String,
        note: String,
        category: WaypointCategory,
        coord: GeoCoord,
        accuracy: Float?,
        photoPaths: List<String> = emptyList()
    ): Long {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val waypointId = db.insertOrThrow(
                "waypoints", null,
                ContentValues().apply {
                    projectId?.let { put("project_id", it) }
                    put("title", title)
                    put("note", note)
                    put("category", category.name)
                    put("lat", coord.lat)
                    put("lon", coord.lon)
                    coord.altitude?.let { put("altitude", it) }
                    accuracy?.let { put("accuracy", it) }
                    put("ts", now)
                    photoPaths.firstOrNull()?.let { put("photo_path", it) }
                }
            )
            photoPaths.distinct().forEachIndexed { index, path ->
                db.insertOrThrow(
                    "waypoint_photos", null,
                    ContentValues().apply {
                        put("waypoint_id", waypointId)
                        put("path", path)
                        put("created_at", now + index)
                    }
                )
            }
            db.setTransactionSuccessful()
            return waypointId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getWaypoints(projectId: Long? = null, filterByProject: Boolean = false): List<Waypoint> {
        val out = mutableListOf<Waypoint>()
        val where = if (filterByProject) {
            if (projectId == null) "project_id IS NULL" else "project_id=?"
        } else null
        val args = if (filterByProject && projectId != null) arrayOf(projectId.toString()) else null
        readableDatabase.query(
            "waypoints",
            arrayOf("id", "project_id", "title", "note", "category", "lat", "lon", "altitude", "accuracy", "ts", "photo_path"),
            where, args, null, null, "ts DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += Waypoint(
                    id = c.getLong(0),
                    projectId = if (c.isNull(1)) null else c.getLong(1),
                    title = c.getString(2),
                    note = c.getString(3),
                    category = runCatching { WaypointCategory.valueOf(c.getString(4)) }.getOrDefault(WaypointCategory.OTHER),
                    lat = c.getDouble(5),
                    lon = c.getDouble(6),
                    altitude = if (c.isNull(7)) null else c.getDouble(7),
                    accuracy = if (c.isNull(8)) null else c.getFloat(8),
                    timestamp = c.getLong(9),
                    photoPaths = getWaypointPhotoPaths(
                        c.getLong(0),
                        if (c.isNull(10)) null else c.getString(10)
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun getWaypointPhotoPaths(waypointId: Long, legacyPath: String? = null): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.query(
            "waypoint_photos",
            arrayOf("path"),
            "waypoint_id=?",
            arrayOf(waypointId.toString()),
            null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        if (out.isEmpty() && !legacyPath.isNullOrBlank()) out += legacyPath
        return out.distinct()
    }

    @Synchronized
    fun deleteWaypoint(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("waypoint_photos", "waypoint_id=?", arrayOf(id.toString()))
            writableDatabase.delete("waypoints", "id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    companion object {
        private const val DB_NAME = "geocampo.db"
        private const val DB_VERSION = 4
    }
}
