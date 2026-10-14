package mx.org.fraac.geocampo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeoReportGenerator(
    private val context: Context,
    private val db: TrackDatabase
) {
    fun generate(
        uri: Uri,
        data: PrintReportData,
        mapBitmap: Bitmap,
        projectId: Long?,
        baseMapMode: BaseMapMode,
        currentFix: TrackPoint?,
        visibleLayerNames: List<String>
    ) {
        val waypoints = db.getWaypoints(projectId, filterByProject = true)
        val tracks = db.getTracks(projectId, filterByProject = true)
        val categories = waypoints.map { it.category }.distinct()

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        drawBackground(canvas)
        drawHeader(canvas, data)
        drawMap(canvas, mapBitmap)
        drawSidePanel(
            canvas = canvas,
            data = data,
            categories = categories,
            waypointCount = waypoints.size,
            trackCount = tracks.size,
            baseMapMode = baseMapMode,
            currentFix = currentFix,
            visibleLayerNames = visibleLayerNames
        )
        drawFooter(canvas)

        document.finishPage(page)

        context.contentResolver.openOutputStream(uri, "w").use { output ->
            requireNotNull(output) { "No se pudo abrir el destino PDF" }
            document.writeTo(output)
        }
        document.close()
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)

        val green = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 59, 42)
        }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 92f, green)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(94, 164, 111)
        }
        canvas.drawRect(0f, 92f, PAGE_WIDTH.toFloat(), 98f, accent)
    }

    private fun drawHeader(canvas: Canvas, data: PrintReportData) {
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.fraac_logo)
        if (logo != null) {
            canvas.drawBitmap(
                logo,
                null,
                RectF(24f, 12f, 96f, 84f),
                Paint(Paint.ANTI_ALIAS_FLAG)
            )
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(data.title, 116f, 43f, titlePaint)

        val orgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(213, 236, 219)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("FOREST RANGERS ALLIANCE A.C.", 116f, 69f, orgPaint)

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(
            "Impresión: " + PRINT_DATE.format(Date()),
            PAGE_WIDTH - 26f,
            40f,
            datePaint
        )
        canvas.drawText(
            "Generado con GeoCampo",
            PAGE_WIDTH - 26f,
            62f,
            datePaint
        )
    }

    private fun drawMap(canvas: Canvas, bitmap: Bitmap) {
        val frame = RectF(28f, 122f, 792f, 688f)
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 238, 233)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(frame, 18f, 18f, framePaint)

        val inner = RectF(
            frame.left + 9f,
            frame.top + 9f,
            frame.right - 9f,
            frame.bottom - 9f
        )

        drawBitmapCenterCrop(canvas, bitmap, inner)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 115, 94)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(frame, 18f, 18f, borderPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 59, 42)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("VISTA CARTOGRÁFICA", 42f, 116f, labelPaint)
    }

    private fun drawSidePanel(
        canvas: Canvas,
        data: PrintReportData,
        categories: List<WaypointCategory>,
        waypointCount: Int,
        trackCount: Int,
        baseMapMode: BaseMapMode,
        currentFix: TrackPoint?,
        visibleLayerNames: List<String>
    ) {
        val panel = RectF(816f, 122f, PAGE_WIDTH - 28f, 688f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(247, 249, 247)
        }
        canvas.drawRoundRect(panel, 18f, 18f, fill)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(218, 228, 220)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        canvas.drawRoundRect(panel, 18f, 18f, border)

        var y = 151f
        val x = 836f
        val width = panel.right - x - 16f

        y = sectionTitle(canvas, "PROYECTO", x, y)
        y = wrapped(
            canvas,
            data.projectName,
            x,
            y,
            width,
            14f,
            Color.rgb(25, 38, 30),
            bold = true,
            maxLines = 2
        ) + 6f

        y = labelValue(canvas, "Elaboró", data.preparedBy, x, y, width)
        y = labelValue(
            canvas,
            "Mapa base",
            when (baseMapMode) {
                BaseMapMode.MAP -> "Cartográfico / PMTiles"
                BaseMapMode.SATELLITE_ONLINE -> "Sentinel-2 online + calles"
                BaseMapMode.SATELLITE_OFFLINE -> "Sentinel-2 offline + calles"
                BaseMapMode.MAPTILER_HD -> "MapTiler Satellite Hybrid HD"
            },
            x,
            y,
            width
        )

        currentFix?.let {
            val gps = String.format(
                Locale.US,
                "%.6f, %.6f%s",
                it.lat,
                it.lon,
                it.accuracy?.let { acc -> "  ±" + acc.toInt() + " m" } ?: ""
            )
            y = labelValue(canvas, "GPS al imprimir", gps, x, y, width)
        }

        y += 2f
        y = sectionTitle(canvas, "DESCRIPCIÓN", x, y)
        y = wrapped(
            canvas,
            data.description.ifBlank { "Sin descripción adicional." },
            x,
            y,
            width,
            11.5f,
            Color.rgb(52, 62, 55),
            maxLines = 5
        ) + 8f

        if (data.observations.isNotBlank()) {
            y = sectionTitle(canvas, "OBSERVACIONES", x, y)
            y = wrapped(
                canvas,
                data.observations,
                x,
                y,
                width,
                10.8f,
                Color.rgb(52, 62, 55),
                maxLines = 4
            ) + 8f
        }

        y = sectionTitle(canvas, "RESUMEN", x, y)
        y = smallStat(canvas, "Puntos georreferenciados", waypointCount.toString(), x, y, width)
        y = smallStat(canvas, "Recorridos GPS", trackCount.toString(), x, y, width)
        y = smallStat(canvas, "Capas visibles", visibleLayerNames.size.toString(), x, y, width)

        y += 4f
        y = sectionTitle(canvas, "SIMBOLOGÍA", x, y)
        y = legendLine(canvas, Color.rgb(13, 121, 245), "●", "Mi ubicación GPS", x, y)
        y = legendLine(canvas, Color.rgb(13, 121, 245), "—", "Recorrido GPS", x, y)

        categories.take(8).forEach { category ->
            y = legendLine(
                canvas,
                categoryColor(category),
                category.glyph,
                category.label,
                x,
                y
            )
        }

        if (visibleLayerNames.isNotEmpty() && y < panel.bottom - 44f) {
            y += 5f
            val layerText =
                "Capas: " + visibleLayerNames.take(4).joinToString(", ") +
                    if (visibleLayerNames.size > 4) "…" else ""
            wrapped(
                canvas,
                layerText,
                x,
                y,
                width,
                9.5f,
                Color.rgb(76, 92, 80),
                maxLines = 2
            )
        }
    }

    private fun drawFooter(canvas: Canvas) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(94, 164, 111)
            strokeWidth = 1.5f
        }
        canvas.drawLine(28f, 716f, PAGE_WIDTH - 28f, 716f, linePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(72, 83, 75)
            textSize = 10f
        }
        canvas.drawText(
            "Forest Rangers Alliance A.C. · GeoCampo · Información geográfica de campo",
            28f,
            743f,
            textPaint
        )
        canvas.drawText(
            "Desarrollada por Reynaldo Ordoñez",
            28f,
            762f,
            textPaint
        )

        val right = Paint(textPaint).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(
            "Documento generado automáticamente · Página 1 de 1",
            PAGE_WIDTH - 28f,
            753f,
            right
        )
    }

    private fun sectionTitle(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 59, 42)
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(text, x, y, paint)
        return y + 18f
    }

    private fun labelValue(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        width: Float
    ): Float {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(91, 108, 96)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(label.uppercase(Locale.ROOT), x, y, labelPaint)

        return wrapped(
            canvas,
            value,
            x,
            y + 14f,
            width,
            11f,
            Color.rgb(35, 45, 38),
            maxLines = 2
        ) + 8f
    }

    private fun smallStat(
        canvas: Canvas,
        label: String,
        value: String,
        x: Float,
        y: Float,
        width: Float
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(54, 67, 58)
            textSize = 10.5f
        }
        canvas.drawText(label, x, y, paint)

        val valuePaint = Paint(paint).apply {
            color = Color.rgb(18, 59, 42)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(value, x + width, y, valuePaint)
        return y + 17f
    }

    private fun legendLine(
        canvas: Canvas,
        color: Int,
        glyph: String,
        label: String,
        x: Float,
        y: Float
    ): Float {
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(x + 8f, y - 4f, 7f, circle)

        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 8f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(glyph.take(2), x + 8f, y - 1f, glyphPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.rgb(53, 65, 57)
            textSize = 10f
        }
        canvas.drawText(label, x + 23f, y, textPaint)
        return y + 17f
    }

    private fun wrapped(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        textSize: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

        val words = text
            .replace("\r", " ")
            .replace("\n", " \n ")
            .split(Regex("\\s+"))

        var line = ""
        var currentY = y
        var lines = 0

        fun drawCurrent() {
            if (line.isNotBlank() && lines < maxLines) {
                canvas.drawText(line.trim(), x, currentY, paint)
                currentY += textSize * 1.32f
                lines++
            }
            line = ""
        }

        for (word in words) {
            if (word == "\n") {
                drawCurrent()
                if (lines >= maxLines) break
                continue
            }

            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                drawCurrent()
                if (lines >= maxLines) break
                line = word
            }
        }
        if (lines < maxLines) drawCurrent()

        return currentY
    }

    private fun drawBitmapCenterCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        dst: RectF
    ) {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstRatio = dst.width() / dst.height()

        val src = if (sourceRatio > dstRatio) {
            val wanted = (bitmap.height * dstRatio).toInt()
            val left = (bitmap.width - wanted) / 2
            android.graphics.Rect(left, 0, left + wanted, bitmap.height)
        } else {
            val wanted = (bitmap.width / dstRatio).toInt()
            val top = (bitmap.height - wanted) / 2
            android.graphics.Rect(0, top, bitmap.width, top + wanted)
        }

        canvas.drawBitmap(bitmap, src, dst, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    private fun categoryColor(category: WaypointCategory): Int =
        when (category) {
            WaypointCategory.WATER, WaypointCategory.CENOTE -> Color.rgb(32, 132, 196)
            WaypointCategory.TREE -> Color.rgb(44, 128, 72)
            WaypointCategory.WILDLIFE -> Color.rgb(104, 92, 66)
            WaypointCategory.HAZARD -> Color.rgb(200, 61, 47)
            WaypointCategory.INFRASTRUCTURE -> Color.rgb(108, 92, 170)
            WaypointCategory.ACCESS, WaypointCategory.FIREBREAK -> Color.rgb(172, 111, 40)
            WaypointCategory.BOUNDARY -> Color.rgb(92, 92, 92)
            WaypointCategory.PHOTO -> Color.rgb(78, 97, 174)
            else -> Color.rgb(21, 105, 84)
        }

    companion object {
        private const val PAGE_WIDTH = 1123
        private const val PAGE_HEIGHT = 794

        private val PRINT_DATE =
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    }
}
