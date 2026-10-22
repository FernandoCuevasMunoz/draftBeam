package cl.fernandocuevas.draftbeam

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import cl.fernandocuevas.draftbeam.ui.theme.DraftBeamColors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.ceil
import kotlin.random.Random

@Composable
fun PlanPreview(
    segments: List<WallSegment>,
    notes: List<PlanNote> = emptyList(),
    selectedSegmentIndex: Int?,
    selectedNoteIndex: Int? = null,
    pendingNotePlacement: Boolean = false,
    selectedStartPoint: PointMm?,
    selectedAngleDegrees: Double,
    viewport: PlanViewportState,
    onDirectionSelected: (WallDirection) -> Unit,
    onSegmentSelected: (Int?) -> Unit,
    onNoteSelected: (Int?) -> Unit = {},
    onNotePlaced: (PointMm) -> Unit = {},
    onStartPointSelected: (PointMm) -> Unit,
    onFingerDrawn: (PointMm, PointMm) -> Unit,
    onEndpointMoveRequested: (Int, WallEndpoint, PointMm, PointMm) -> Unit,
    onConnectionRequested: (PointMm, PointMm, PointMm) -> Unit,
    modifier: Modifier = Modifier
) {
    val draftSegment = remember { mutableStateOf<WallSegment?>(null) }
    val endpointDragPreview = remember { mutableStateOf<EndpointDragPreview?>(null) }
    val magnifier = remember { mutableStateOf<MagnifierState?>(null) }
    val centerCoordinate = viewport.centerPoint
    val canvasBackground = DraftBeamColors.BackgroundDeep
    val canvasTextColor = DraftBeamColors.TextSecondary

    Box(modifier = modifier) {
      Canvas(
        modifier = Modifier.matchParentSize()
            .background(canvasBackground)
            .onSizeChanged { viewport.updateCanvasSize(it, segments, notes) }
            .pointerInput(segments, notes, selectedSegmentIndex, selectedNoteIndex, pendingNotePlacement, selectedStartPoint, viewport) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val size = this.size
                    val endpointSnapTolerancePx = ENDPOINT_SNAP_TOLERANCE_DP.dp.toPx()
                    val noteIndex = if (pendingNotePlacement) null else findNoteAt(
                        notes, down.position, size, viewport, density = 1.dp.toPx()
                    )
                    val selectedEndpoint = if (pendingNotePlacement || noteIndex != null) null else selectedSegmentIndex
                        ?.let { index ->
                            segments.getOrNull(index)?.let { segment ->
                                listOf(WallEndpoint.START to segment.start, WallEndpoint.END to segment.end)
                                    .map { (kind, point) ->
                                        kind to (point to (down.position - viewport.project(point, size)).getDistance())
                                    }
                                    .filter { (_, pointAndDistance) -> pointAndDistance.second <= 34.dp.toPx() }
                                    .minByOrNull { (_, pointAndDistance) -> pointAndDistance.second }
                                    ?.let { (kind, pointAndDistance) -> Triple(index, kind, pointAndDistance.first) }
                            }
                        }
                    val endpoint = if (pendingNotePlacement || noteIndex != null) null else selectedEndpoint?.third ?: findEndpointAt(
                        segments, down.position, size, viewport, 34.dp.toPx()
                    )
                    val segmentHit = if (pendingNotePlacement || noteIndex != null) null else findSegmentAt(
                        segments, down.position, size, viewport, 24.dp.toPx()
                    )
                    val segmentIndex = if (endpoint == null) segmentHit?.index else null
                    var mode = GestureMode.TAP
                    var lastPosition = down.position
                    var previousPinchCentroid = Offset.Zero
                    var previousPinchDistance = 0f
                    var pinchInitialized = false
                    var pressed = true

                    while (pressed) {
                        val event = awaitPointerEvent()

                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.size >= 2) {
                            mode = GestureMode.PINCH
                            val first = activePointers[0].position
                            val second = activePointers[1].position
                            val centroid = Offset((first.x + second.x) / 2f, (first.y + second.y) / 2f)
                            val distance = (first - second).getDistance().coerceAtLeast(1f)

                            if (pinchInitialized) {
                                viewport.zoomAround(
                                    oldCentroid = previousPinchCentroid,
                                    newCentroid = centroid,
                                    zoomFactor = distance / previousPinchDistance,
                                    size = size
                                )
                            }
                            previousPinchCentroid = centroid
                            previousPinchDistance = distance
                            pinchInitialized = true
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        if (mode == GestureMode.PINCH) {
                            event.changes.forEach { it.consume() }
                            pressed = activePointers.isNotEmpty()
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val currentPosition = change.position
                        val movement = (currentPosition - down.position).getDistance()

                        when {
                            endpoint != null && mode == GestureMode.TAP && movement > viewConfiguration.touchSlop -> {
                                if (selectedEndpoint != null) {
                                    mode = GestureMode.MOVE_ENDPOINT
                                    endpointDragPreview.value = EndpointDragPreview(
                                        selectedEndpoint.first,
                                        selectedEndpoint.second,
                                        endpoint,
                                        viewport.unproject(currentPosition, size)
                                    )
                                } else {
                                    mode = GestureMode.DRAW
                                    val worldEnd = viewport.unproject(currentPosition, size)
                                    draftSegment.value = makePreviewSegment(endpoint, worldEnd)
                                    magnifier.value = findNearbyEndpoint(
                                        segments, endpoint, worldEnd, size, viewport, endpointSnapTolerancePx
                                    )
                                        ?.let { MagnifierState(currentPosition, worldEnd, it) }
                                }
                                change.consume()
                            }
                            endpoint == null && mode == GestureMode.TAP && movement > viewConfiguration.touchSlop -> {
                                mode = GestureMode.PAN
                                viewport.panBy(currentPosition - down.position)
                                change.consume()
                            }
                            mode == GestureMode.PAN -> {
                                viewport.panBy(currentPosition - lastPosition)
                                change.consume()
                            }
                            mode == GestureMode.DRAW -> {
                                val worldEnd = viewport.unproject(currentPosition, size)
                                draftSegment.value = makePreviewSegment(endpoint!!, worldEnd)
                                magnifier.value = findNearbyEndpoint(
                                    segments, endpoint, worldEnd, size, viewport, endpointSnapTolerancePx
                                )
                                    ?.let { MagnifierState(currentPosition, worldEnd, it) }
                                change.consume()
                            }
                            mode == GestureMode.MOVE_ENDPOINT -> {
                                endpointDragPreview.value = endpointDragPreview.value?.copy(
                                    destination = viewport.unproject(currentPosition, size)
                                )
                                change.consume()
                            }
                        }
                        lastPosition = currentPosition

                        if (!change.pressed) {
                            pressed = false
                            when (mode) {
                                GestureMode.TAP -> {
                                    if (pendingNotePlacement) {
                                        onNotePlaced(viewport.unproject(down.position, size))
                                    } else if (noteIndex != null) {
                                        onNoteSelected(noteIndex)
                                    } else if (endpoint != null) {
                                        if (selectedEndpoint != null) {
                                            onSegmentSelected(selectedEndpoint.first)
                                        } else if (
                                            segmentHit != null && shouldSelectShortLineOnTap(
                                                lineLengthPx = segmentHit.lineLengthPx,
                                                nearestEndpointDistancePx = (down.position - viewport.project(endpoint, size)).getDistance(),
                                                endpointTargetPx = 34.dp.toPx()
                                            )
                                        ) {
                                            onSegmentSelected(segmentHit.index)
                                        } else {
                                            onSegmentSelected(null)
                                            onStartPointSelected(endpoint)
                                        }
                                    } else {
                                        onSegmentSelected(segmentIndex)
                                    }
                                }
                                GestureMode.DRAW -> {
                                    draftSegment.value = null
                                    magnifier.value = null
                                    val drawnEnd = viewport.unproject(currentPosition, size)
                                    val nearbyEndpoint = findNearbyEndpoint(
                                        segments = segments,
                                        startPoint = endpoint!!,
                                        endPoint = drawnEnd,
                                        canvasSize = size,
                                        viewport = viewport,
                                        tolerancePx = endpointSnapTolerancePx
                                    )
                                    if (nearbyEndpoint != null) {
                                        onConnectionRequested(endpoint, drawnEnd, nearbyEndpoint)
                                    } else {
                                        onFingerDrawn(endpoint, drawnEnd)
                                    }
                                }
                                GestureMode.MOVE_ENDPOINT -> {
                                    val preview = endpointDragPreview.value
                                    endpointDragPreview.value = null
                                    preview?.let {
                                        onEndpointMoveRequested(
                                            it.segmentIndex,
                                            it.endpoint,
                                            it.origin,
                                            viewport.unproject(currentPosition, size)
                                        )
                                    }
                                }
                                GestureMode.PAN -> Unit
                                GestureMode.PINCH -> Unit
                            }
                        }
                    }

                    draftSegment.value = null
                    endpointDragPreview.value = null
                    magnifier.value = null
                }
            }
    ) {
        val size = IntSize(size.width.toInt(), size.height.toInt())
        val renderSegments = endpointDragPreview.value?.let { preview ->
            runCatching {
                PlanGeometry.moveEndpoint(
                    segments,
                    preview.segmentIndex,
                    preview.endpoint,
                    preview.destination
                )
            }.getOrNull()
        } ?: segments
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val clipSave = nativeCanvas.save()
        nativeCanvas.clipRect(0f, 0f, size.width.toFloat(), size.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13.dp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val axisColor = canvasTextColor

        // Choose a readable metric interval from the current zoom, aligned to the origin.
        val visibleTopLeft = viewport.unproject(Offset.Zero, size)
        val visibleBottomRight = viewport.unproject(Offset(size.width.toFloat(), size.height.toFloat()), size)
        val gridColor = DraftBeamColors.CadGrid.copy(alpha = 0.42f)
        val majorGridColor = DraftBeamColors.CadGrid
        val gridStepMm = selectGridStepMm(24.dp.toPx(), viewport.pixelsPerMillimeter)
        val firstGridX = kotlin.math.ceil(min(visibleTopLeft.x, visibleBottomRight.x) / gridStepMm).toLong()
        val lastGridX = kotlin.math.floor(max(visibleTopLeft.x, visibleBottomRight.x) / gridStepMm).toLong()
        if (lastGridX - firstGridX <= 200) {
            for (gridIndex in firstGridX..lastGridX) {
                val x = viewport.project(PointMm(gridIndex * gridStepMm, 0.0), size).x
                val major = gridIndex % 5L == 0L
                drawLine(if (major) majorGridColor else gridColor, Offset(x, 0f), Offset(x, size.height.toFloat()), if (major) 1.dp.toPx() else 0.5.dp.toPx())
            }
        }
        val firstGridY = kotlin.math.ceil(min(visibleBottomRight.y, visibleTopLeft.y) / gridStepMm).toLong()
        val lastGridY = kotlin.math.floor(max(visibleBottomRight.y, visibleTopLeft.y) / gridStepMm).toLong()
        if (lastGridY - firstGridY <= 200) {
            for (gridIndex in firstGridY..lastGridY) {
                val y = viewport.project(PointMm(0.0, gridIndex * gridStepMm), size).y
                val major = gridIndex % 5L == 0L
                drawLine(if (major) majorGridColor else gridColor, Offset(0f, y), Offset(size.width.toFloat(), y), if (major) 1.dp.toPx() else 0.5.dp.toPx())
            }
        }

        // Scale ruler labels/ticks along the lower and left canvas edges.
        val edgeInset = 4.dp.toPx()
        val controlClearance = 58.dp.toPx()
        val tickLength = 4.dp.toPx()
        paint.color = canvasTextColor.toArgb()
        paint.textSize = 9.dp.toPx()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        val firstXDivision = kotlin.math.ceil(min(visibleTopLeft.x, visibleBottomRight.x) / gridStepMm).toLong()
        val lastXDivision = kotlin.math.floor(max(visibleTopLeft.x, visibleBottomRight.x) / gridStepMm).toLong()
        if (lastXDivision - firstXDivision <= 200) {
            for (division in firstXDivision..lastXDivision) {
                val coordinateMm = division * gridStepMm
                val x = viewport.project(PointMm(coordinateMm, 0.0), size).x
                drawLine(canvasTextColor, Offset(x, size.height - tickLength), Offset(x, size.height.toFloat()), 0.75.dp.toPx())
                if (division != 0L && x > controlClearance && x < size.width - controlClearance) {
                    paint.textAlign = Paint.Align.CENTER
                    nativeCanvas.drawText(formatScaleCoordinate(coordinateMm, gridStepMm), x, size.height - edgeInset, paint)
                }
            }
        }
        val firstYDivision = kotlin.math.ceil(min(visibleBottomRight.y, visibleTopLeft.y) / gridStepMm).toLong()
        val lastYDivision = kotlin.math.floor(max(visibleBottomRight.y, visibleTopLeft.y) / gridStepMm).toLong()
        if (lastYDivision - firstYDivision <= 200) {
            for (division in firstYDivision..lastYDivision) {
                val coordinateMm = division * gridStepMm
                val y = viewport.project(PointMm(0.0, coordinateMm), size).y
                drawLine(canvasTextColor, Offset(0f, y), Offset(tickLength, y), 0.75.dp.toPx())
                if (division != 0L && y > controlClearance && y < size.height - controlClearance) {
                    paint.textAlign = Paint.Align.LEFT
                    nativeCanvas.drawText(formatScaleCoordinate(coordinateMm, gridStepMm), edgeInset, y + paint.textSize / 3f, paint)
                }
            }
        }
        paint.textSize = 13.dp.toPx()
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        val originOnScreen = viewport.project(PointMm(0.0, 0.0), size)
        if (originOnScreen.y in 0f..size.height.toFloat()) {
            drawLine(Color(0xFF9E9E9E), Offset(0f, originOnScreen.y), Offset(size.width.toFloat(), originOnScreen.y), 1.dp.toPx())
        }
        if (originOnScreen.x in 0f..size.width.toFloat()) {
            drawLine(Color(0xFF9E9E9E), Offset(originOnScreen.x, size.height.toFloat()), Offset(originOnScreen.x, 0f), 1.dp.toPx())
        }
        if (originOnScreen.x in 0f..size.width.toFloat() && originOnScreen.y in 0f..size.height.toFloat()) {
            paint.color = axisColor.toArgb()
            paint.textAlign = Paint.Align.LEFT
            nativeCanvas.drawText("0,0", originOnScreen.x + 6.dp.toPx(), originOnScreen.y + 15.dp.toPx(), paint)
        }

        val wallShapes = PlanGeometry.wallFootprints(renderSegments)
        val dimensionTextSize = scaledDimensionTextSize(
            pixelsPerMillimeter = viewport.pixelsPerMillimeter,
            density = 1.dp.toPx()
        )
        val dimensionLabelOffset = scaledDimensionLabelOffset(
            pixelsPerMillimeter = viewport.pixelsPerMillimeter,
            density = 1.dp.toPx()
        )
        renderSegments.forEachIndexed { index, segment ->
            val start = viewport.project(segment.start, size)
            val end = viewport.project(segment.end, size)
            val lineColor = if (index == selectedSegmentIndex) WALL_ORANGE else WALL_COLOR
            val strokeWidth = if (index == selectedSegmentIndex) 7.dp.toPx() else 4.dp.toPx()
            val worldFootprints = wallShapes[index]
            if (worldFootprints.isNotEmpty()) {
                val screenFootprints = worldFootprints.map { polygon -> polygon.map { viewport.project(it, size) } }
                val wallPath = Path().apply {
                    screenFootprints.forEach { polygon ->
                        if (polygon.size >= 3) {
                            moveTo(polygon.first().x, polygon.first().y)
                            polygon.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                    }
                }
                drawConcreteWallPattern(
                    canvas = nativeCanvas,
                    clipPath = wallPath,
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                    tileSizePx = (500f * viewport.pixelsPerMillimeter).coerceIn(36.dp.toPx(), 90.dp.toPx()),
                    color = WALL_ORANGE.toArgb(),
                    strokeWidth = 1.dp.toPx()
                )
                // Both long edges of a wall with width are explicitly visible.
                drawLine(color = lineColor, start = start, end = end, strokeWidth = strokeWidth)
                PlanGeometry.wallCorners(segment).takeIf { it.size == 4 }?.let { corners ->
                    // Close both ends of the wall footprint, joining its long edges.
                    drawLine(
                        color = lineColor,
                        start = viewport.project(corners[0], size),
                        end = viewport.project(corners[3], size),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = lineColor,
                        start = viewport.project(corners[2], size),
                        end = viewport.project(corners[3], size),
                        strokeWidth = strokeWidth
                    )
                    drawLine(
                        color = lineColor,
                        start = viewport.project(corners[1], size),
                        end = viewport.project(corners[2], size),
                        strokeWidth = strokeWidth
                    )
                }
            } else {
                drawLine(color = lineColor, start = start, end = end, strokeWidth = strokeWidth)
            }
            paint.color = lineColor.toArgb()
            paint.textSize = dimensionTextSize
            paint.textAlign = Paint.Align.CENTER
            drawSegmentLabel(nativeCanvas, paint, segment, start, end, segment.growthSide, dimensionLabelOffset)
        }

        selectedSegmentIndex?.let { index ->
            renderSegments.getOrNull(index)?.let { segment ->
                listOf(segment.start, segment.end).forEach { endpoint ->
                    val point = viewport.project(endpoint, size)
                    drawCircle(DraftBeamColors.BackgroundDeep, 8.dp.toPx(), point)
                    drawCircle(WALL_ORANGE, 6.dp.toPx(), point)
                    drawCircle(DraftBeamColors.BackgroundDeep, 2.5.dp.toPx(), point)
                }
            }
        }

        val connectedPoints = renderSegments
            .flatMap { listOf(it.start, it.end) }
            .groupingBy { it }
            .eachCount()
            .filterValues { endpointCount -> endpointCount > 1 }
            .keys
        connectedPoints.forEach { point ->
            // Screen-only junction marker: it is intentionally not part of wall geometry.
            drawCircle(
                color = Color(0xFFFFD600),
                radius = 2.dp.toPx(),
                center = viewport.project(point, size)
            )
        }

        val availableEndpoints = PlanGeometry.wallInteractionPoints(renderSegments)
        availableEndpoints.forEach { endpoint ->
            val point = viewport.project(endpoint, size)
            drawCircle(canvasBackground, 8.dp.toPx(), point)
            drawCircle(Color(0xFF61D5FF), 6.dp.toPx(), point)
            drawCircle(canvasBackground, 2.dp.toPx(), point)
        }

        val activePoint = selectedStartPoint
            ?.let { point -> endpointDragPreview.value?.takeIf { it.origin == point }?.destination ?: point }
            ?: renderSegments.lastOrNull()?.end
            ?: PointMm(0.0, 0.0)
        val target = viewport.project(activePoint, size)
        val targetHalf = 9.dp.toPx()
        drawRect(canvasBackground, Offset(target.x - targetHalf, target.y - targetHalf), androidx.compose.ui.geometry.Size(targetHalf * 2, targetHalf * 2))
        drawRect(Color(0xFF2E7D32), Offset(target.x - targetHalf, target.y - targetHalf), androidx.compose.ui.geometry.Size(targetHalf * 2, targetHalf * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        drawLine(Color(0xFF2E7D32), Offset(target.x - 5.dp.toPx(), target.y), Offset(target.x + 5.dp.toPx(), target.y), 2.dp.toPx())
        drawLine(Color(0xFF2E7D32), Offset(target.x, target.y - 5.dp.toPx()), Offset(target.x, target.y + 5.dp.toPx()), 2.dp.toPx())
        drawCircle(WALL_ORANGE, 3.dp.toPx(), originOnScreen)

        val center = Offset(size.width / 2f, size.height / 2f)
        val crossHalf = 9.dp.toPx()
        drawLine(axisColor, Offset(center.x - crossHalf, center.y), Offset(center.x + crossHalf, center.y), 2.dp.toPx())
        drawLine(axisColor, Offset(center.x, center.y - crossHalf), Offset(center.x, center.y + crossHalf), 2.dp.toPx())
        val centerLabel = "${centerCoordinate.x.roundToLong()},${centerCoordinate.y.roundToLong()}"
        paint.textSize = 10.dp.toPx()
        val labelWidth = paint.measureText(centerLabel)
        val labelTop = center.y + 12.dp.toPx()
        drawRoundRect(
            color = canvasBackground.copy(alpha = 0.96f),
            topLeft = Offset(center.x - labelWidth / 2f - 5.dp.toPx(), labelTop),
            size = androidx.compose.ui.geometry.Size(labelWidth + 10.dp.toPx(), paint.textSize + 6.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )
        paint.color = axisColor.toArgb()
        paint.textAlign = Paint.Align.CENTER
        nativeCanvas.drawText(centerLabel, center.x, labelTop + paint.textSize, paint)
        paint.textSize = 13.dp.toPx()

        notes.forEachIndexed { index, note ->
            drawPlanNote(
                canvas = nativeCanvas,
                note = note,
                selected = selectedNoteIndex == index,
                anchor = viewport.project(note.position, size),
                pixelsPerMillimeter = viewport.pixelsPerMillimeter,
                density = 1.dp.toPx()
            )
        }

        draftSegment.value?.let { draft ->
            val start = viewport.project(draft.start, size)
            val end = viewport.project(draft.end, size)
            drawLine(WALL_ORANGE, start, end, 3.dp.toPx())
            paint.color = WALL_ORANGE.toArgb()
            paint.textSize = dimensionTextSize
            drawSegmentLabel(nativeCanvas, paint, draft, start, end, draft.growthSide, dimensionLabelOffset)
        }

        magnifier.value?.let { lens ->
            val radius = 58.dp.toPx()
            val margin = 14.dp.toPx()
            val lensX = if (lens.finger.x < size.width * 0.55f) {
                (lens.finger.x + radius + margin).coerceAtMost(size.width - radius)
            } else {
                (lens.finger.x - radius - margin).coerceAtLeast(radius)
            }
            val lensY = if (lens.finger.y > radius * 2f) {
                lens.finger.y - radius - margin
            } else {
                lens.finger.y + radius + margin
            }.coerceIn(radius, size.height - radius)
            val lensCenter = Offset(lensX, lensY)
            val movingEndOnScreen = viewport.project(lens.movingEnd, size)
            val targetOnScreen = viewport.project(lens.target, size)
            val magnification = 3f
            fun magnify(point: Offset) = lensCenter + (point - movingEndOnScreen) * magnification

            val lensClip = Path().apply {
                addOval(Rect(lensCenter.x - radius, lensCenter.y - radius, lensCenter.x + radius, lensCenter.y + radius))
            }
            drawContext.canvas.save()
            drawContext.canvas.clipPath(lensClip)
            drawCircle(canvasBackground, radius, lensCenter)
            wallShapes.forEachIndexed { wallIndex, polygons ->
                val lineColor = WALL_COLOR
                polygons.forEach { polygon ->
                    val points = polygon.map { point -> magnify(viewport.project(point, size)) }
                    if (points.size >= 3) {
                        val lensPath = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawConcreteWallPattern(
                            canvas = drawContext.canvas.nativeCanvas,
                            clipPath = lensPath,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            tileSizePx = 42.dp.toPx(),
                            color = WALL_ORANGE.toArgb(),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
                val segment = segments[wallIndex]
                drawLine(
                    lineColor,
                    magnify(viewport.project(segment.start, size)),
                    magnify(viewport.project(segment.end, size)),
                    strokeWidth = 3.dp.toPx()
                )
            }
            draftSegment.value?.let { draft ->
                drawLine(
                    WALL_ORANGE,
                    magnify(viewport.project(draft.start, size)),
                    magnify(viewport.project(draft.end, size)),
                    strokeWidth = 3.dp.toPx()
                )
            }
            drawCircle(Color(0xFFFFD600), 5.dp.toPx(), magnify(targetOnScreen))
            drawCircle(DRAFT_WALL_COLOR, 4.dp.toPx(), magnify(lens.finger))
            drawContext.canvas.restore()
            drawCircle(WALL_COLOR, radius, lensCenter, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawLine(WALL_COLOR, Offset(lensCenter.x - 8.dp.toPx(), lensCenter.y), Offset(lensCenter.x + 8.dp.toPx(), lensCenter.y), 1.dp.toPx())
            drawLine(WALL_COLOR, Offset(lensCenter.x, lensCenter.y - 8.dp.toPx()), Offset(lensCenter.x, lensCenter.y + 8.dp.toPx()), 1.dp.toPx())
        }
        nativeCanvas.restoreToCount(clipSave)
    }
        DirectionEdgeButton(
            direction = WallDirection.X_NEGATIVE,
            selectedAngleDegrees = selectedAngleDegrees,
            onDirectionSelected = onDirectionSelected,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp, end = 4.dp),
            canvasBackground = canvasBackground
        )
        DirectionEdgeButton(
            direction = WallDirection.X_POSITIVE,
            selectedAngleDegrees = selectedAngleDegrees,
            onDirectionSelected = onDirectionSelected,
            modifier = Modifier.align(Alignment.CenterEnd).padding(start = 4.dp, end = 12.dp),
            canvasBackground = canvasBackground
        )
        DirectionEdgeButton(
            direction = WallDirection.Y_POSITIVE,
            selectedAngleDegrees = selectedAngleDegrees,
            onDirectionSelected = onDirectionSelected,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp, bottom = 4.dp),
            canvasBackground = canvasBackground
        )
        DirectionEdgeButton(
            direction = WallDirection.Y_NEGATIVE,
            selectedAngleDegrees = selectedAngleDegrees,
            onDirectionSelected = onDirectionSelected,
            modifier = Modifier.align(Alignment.BottomCenter).padding(top = 4.dp, bottom = 12.dp),
            canvasBackground = canvasBackground
        )
    }
}

@Composable
private fun DirectionEdgeButton(
    direction: WallDirection,
    selectedAngleDegrees: Double,
    onDirectionSelected: (WallDirection) -> Unit,
    canvasBackground: Color,
    modifier: Modifier = Modifier
) {
    val difference = abs(PlanGeometry.normalizeAngle(selectedAngleDegrees) - direction.angleDegrees)
    val selected = min(difference, 360.0 - difference) < 0.1
    OutlinedButton(
        onClick = { onDirectionSelected(direction) },
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) DraftBeamColors.PrimaryLight else DraftBeamColors.TechnicalBlue
        ),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) DraftBeamColors.SurfaceElevated else canvasBackground.copy(alpha = 0.94f),
            contentColor = if (selected) DraftBeamColors.LaserCyan else DraftBeamColors.TextSecondary
        )
    ) {
        Text(
            when (direction) {
                WallDirection.X_NEGATIVE -> "← ${direction.label}"
                WallDirection.X_POSITIVE -> "${direction.label} →"
                WallDirection.Y_POSITIVE -> "↑ ${direction.label}"
                WallDirection.Y_NEGATIVE -> "↓ ${direction.label}"
            }
        )
    }
}

private val WALL_COLOR = Color(0xFF3478F6)
private val WALL_ORANGE = Color(0xFFE65100)
private val DRAFT_WALL_COLOR = Color(0xFF2E7D32)

private fun makePreviewSegment(start: PointMm, end: PointMm): WallSegment? =
    runCatching { PlanGeometry.segmentFromDrag(start, end) }.getOrNull()

private enum class GestureMode { TAP, DRAW, MOVE_ENDPOINT, PAN, PINCH }

private data class EndpointDragPreview(
    val segmentIndex: Int,
    val endpoint: WallEndpoint,
    val origin: PointMm,
    val destination: PointMm
)

private data class MagnifierState(
    val finger: Offset,
    val movingEnd: PointMm,
    val target: PointMm
)

private fun drawConcreteWallPattern(
    canvas: android.graphics.Canvas,
    clipPath: Path,
    width: Float,
    height: Float,
    tileSizePx: Float,
    color: Int,
    strokeWidth: Float
) {
    val saveCount = canvas.save()
    canvas.clipPath(clipPath.asAndroidPath())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.strokeWidth = strokeWidth
        style = Paint.Style.STROKE
    }
    val tileCountX = kotlin.math.ceil(width / tileSizePx).toInt() + 1
    val tileCountY = kotlin.math.ceil(height / tileSizePx).toInt() + 1
    for (row in -1 until tileCountY) {
        for (column in -1 until tileCountX) {
            val random = Random(column * 73_856_093 xor row * 19_349_663)
            val tileX = column * tileSizePx
            val tileY = row * tileSizePx
            repeat(14) {
                val centerX = tileX + random.nextFloat() * tileSizePx
                val centerY = tileY + random.nextFloat() * tileSizePx
                val radius = tileSizePx * (0.025f + random.nextFloat() * 0.035f)
                val angle = random.nextFloat() * (Math.PI.toFloat() * 2f)
                val path = android.graphics.Path()
                for (corner in 0..2) {
                    val cornerAngle = angle + corner * (Math.PI.toFloat() * 2f / 3f)
                    val pointX = centerX + kotlin.math.cos(cornerAngle) * radius
                    val pointY = centerY + kotlin.math.sin(cornerAngle) * radius
                    if (corner == 0) path.moveTo(pointX, pointY) else path.lineTo(pointX, pointY)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }
    canvas.restoreToCount(saveCount)
}

internal fun scaledDimensionTextSize(
    pixelsPerMillimeter: Float,
    density: Float,
    baseTextHeightMm: Float = 130f,
    minTextSizeDp: Float = 5f,
    maxTextSizeDp: Float = 24f
): Float = (baseTextHeightMm * pixelsPerMillimeter)
    .coerceIn(minTextSizeDp * density, maxTextSizeDp * density)

internal fun scaledDimensionLabelOffset(
    pixelsPerMillimeter: Float,
    density: Float,
    offsetMm: Float = 80f,
    minOffsetDp: Float = 2f
): Float = (offsetMm * pixelsPerMillimeter).coerceAtLeast(minOffsetDp * density)

private fun drawSegmentLabel(
    canvas: android.graphics.Canvas,
    paint: Paint,
    segment: WallSegment,
    start: Offset,
    end: Offset,
    growthSide: WallGrowthSide,
    offset: Float
) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val diagonal = abs(segment.angleDegrees % 90.0) > 0.01
    val label = if (diagonal) "${segment.lengthMm} · ${segment.angleDegrees.formatAngle()}°" else segment.lengthMm.toString()
    val midX = (start.x + end.x) / 2f
    val midY = (start.y + end.y) / 2f
    val vertical = abs(dx) < 0.01f
    val length = hypot(dx, dy).coerceAtLeast(1f)
    val leftNormalX = dy / length
    val leftNormalY = -dx / length
    val growthSign = if (growthSide == WallGrowthSide.LEFT) 1f else -1f
    // Android positions text by its baseline. Compensate its height in every
    // orientation so the visible glyph edge keeps a consistent gap from the line.
    val baselineOffset = offset + paint.textSize
    val labelX = midX - leftNormalX * growthSign * baselineOffset
    val labelY = midY - leftNormalY * growthSign * baselineOffset
    paint.textAlign = Paint.Align.CENTER
    if (!diagonal && !vertical) {
        canvas.drawText(label, labelX, labelY, paint)
        return
    }
    if (diagonal && paint.measureText(label) > hypot(dx, dy)) {
        // Keep labels on short diagonal walls centered instead of letting the
        // rotated text appear to belong to one endpoint.
        canvas.drawText(label, labelX, labelY, paint)
        return
    }
    // Vertical labels keep their established orientation; diagonal labels are
    // normalized so the text never appears upside down when the wall reverses.
    val screenAngle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
    val angle = if (vertical) -90f else if (diagonal) {
        PlanGeometry.uprightLabelRotationDegrees(screenAngle).toFloat()
    } else {
        screenAngle.toFloat()
    }
    canvas.save()
    canvas.rotate(angle, labelX, labelY)
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(label, labelX, labelY, paint)
    canvas.restore()
}

class PlanViewportState {
    var pixelsPerMillimeter by mutableFloatStateOf(0f)
        private set
    var centerXMillimeters by mutableFloatStateOf(0f)
        private set
    var centerYMillimeters by mutableFloatStateOf(0f)
        private set

    val centerPoint: PointMm
        get() = PointMm(centerXMillimeters.toDouble(), centerYMillimeters.toDouble())

    private var canvasWidth = 0f
    private var canvasHeight = 0f

    fun updateCanvasSize(size: IntSize, segments: List<WallSegment>, notes: List<PlanNote> = emptyList()) {
        canvasWidth = size.width.toFloat()
        canvasHeight = size.height.toFloat()
        if (pixelsPerMillimeter <= 0f && canvasWidth > 0f && canvasHeight > 0f) fitToContent(segments, notes)
    }

    fun fitToContent(segments: List<WallSegment>, notes: List<PlanNote> = emptyList()) {
        if (canvasWidth <= 0f || canvasHeight <= 0f) return

        val segmentPoints = segments.flatMap { segment ->
            listOf(segment.start, segment.end) + PlanGeometry.wallCorners(segment)
        }
        val notePoints = notes.flatMap { note ->
            val width = note.textWidthMm
            val height = note.heightMm
            val radians = Math.toRadians(note.rotationDegrees)
            val cosine = kotlin.math.cos(radians)
            val sine = kotlin.math.sin(radians)
            listOf(
                PointMm(0.0, -height * NOTE_TEXT_DESCENT_FACTOR),
                PointMm(width, -height * NOTE_TEXT_DESCENT_FACTOR),
                PointMm(width, height),
                PointMm(0.0, height)
            ).map { local ->
                PointMm(
                    note.position.x + local.x * cosine - local.y * sine,
                    note.position.y + local.x * sine + local.y * cosine
                )
            }
        }
        val points = when {
            segmentPoints.isNotEmpty() || notePoints.isNotEmpty() -> segmentPoints + notePoints
            else -> listOf(
                PointMm(-DEFAULT_VIEW_HALF_SPAN_MM, -DEFAULT_VIEW_HALF_SPAN_MM),
                PointMm(DEFAULT_VIEW_HALF_SPAN_MM, DEFAULT_VIEW_HALF_SPAN_MM)
            )
        }
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = maxX - minX
        val spanY = maxY - minY
        // Keep a small margin while using the full drawable area. A zero-sized axis
        // (for example, a single horizontal wall) must not limit the zoom.
        val paddingFraction = if (segments.isEmpty()) EMPTY_VIEW_PADDING_FRACTION else FIT_PADDING_FRACTION
        val paddingX = max(12f, canvasWidth * paddingFraction)
        val paddingY = max(12f, canvasHeight * paddingFraction)
        val availableWidth = max(1f, canvasWidth - paddingX * 2)
        val availableHeight = max(1f, canvasHeight - paddingY * 2)
        val horizontalScale = if (spanX > 0.0) availableWidth / spanX.toFloat() else Float.POSITIVE_INFINITY
        val verticalScale = if (spanY > 0.0) availableHeight / spanY.toFloat() else Float.POSITIVE_INFINITY

        pixelsPerMillimeter = min(horizontalScale, verticalScale)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerXMillimeters = ((minX + maxX) / 2.0).toFloat()
        centerYMillimeters = ((minY + maxY) / 2.0).toFloat()
    }

    fun project(point: PointMm, size: IntSize): Offset = Offset(
        x = size.width / 2f + (point.x.toFloat() - centerXMillimeters) * pixelsPerMillimeter,
        y = size.height / 2f - (point.y.toFloat() - centerYMillimeters) * pixelsPerMillimeter
    )

    fun unproject(point: Offset, size: IntSize): PointMm = PointMm(
        x = (centerXMillimeters + (point.x - size.width / 2f) / pixelsPerMillimeter).toDouble(),
        y = (centerYMillimeters - (point.y - size.height / 2f) / pixelsPerMillimeter).toDouble()
    )

    fun panBy(screenDelta: Offset) {
        if (pixelsPerMillimeter <= 0f) return
        centerXMillimeters -= screenDelta.x / pixelsPerMillimeter
        centerYMillimeters += screenDelta.y / pixelsPerMillimeter
    }

    fun zoomAround(oldCentroid: Offset, newCentroid: Offset, zoomFactor: Float, size: IntSize) {
        if (pixelsPerMillimeter <= 0f) return
        val focusPoint = unproject(oldCentroid, size)
        pixelsPerMillimeter = (pixelsPerMillimeter * zoomFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        centerXMillimeters = focusPoint.x.toFloat() - (newCentroid.x - size.width / 2f) / pixelsPerMillimeter
        centerYMillimeters = focusPoint.y.toFloat() + (newCentroid.y - size.height / 2f) / pixelsPerMillimeter
    }

    private companion object {
        const val FIT_PADDING_FRACTION = 0.06f
        const val EMPTY_VIEW_PADDING_FRACTION = 0.18f
        const val MIN_ZOOM = 0.005f
        const val MAX_ZOOM = 5f
    }
}

private fun drawPlanNote(
    canvas: android.graphics.Canvas,
    note: PlanNote,
    selected: Boolean,
    anchor: Offset,
    pixelsPerMillimeter: Float,
    density: Float
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (if (selected) WALL_ORANGE else Color(0xFF61D5FF)).toArgb()
        textSize = noteFontSize(note, pixelsPerMillimeter, density)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    val metrics = paint.fontMetrics
    val padding = 5f * density
    val bounds = RectF(
        anchor.x - padding,
        anchor.y + metrics.ascent - padding,
        anchor.x + paint.measureText(note.text) + padding,
        anchor.y + metrics.descent + padding
    )
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DraftBeamColors.BackgroundDeep.copy(alpha = 0.92f).toArgb() }
    val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paint.color
        style = Paint.Style.STROKE
        strokeWidth = if (selected) 2f * density else 1f * density
    }
    canvas.save()
    canvas.rotate(-note.rotationDegrees.toFloat(), anchor.x, anchor.y)
    canvas.drawRoundRect(bounds, 4f * density, 4f * density, background)
    canvas.drawRoundRect(bounds, 4f * density, 4f * density, outline)
    canvas.drawText(note.text, anchor.x, anchor.y, paint)
    canvas.restore()
}

private fun noteFontSize(note: PlanNote, pixelsPerMillimeter: Float, density: Float): Float =
    (note.heightMm.toFloat() * pixelsPerMillimeter).coerceIn(14f * density, 48f * density)

internal fun findNoteAt(
    notes: List<PlanNote>,
    tap: Offset,
    canvasSize: IntSize,
    viewport: PlanViewportState,
    density: Float
): Int? {
    return notes.mapIndexedNotNull { index, note ->
        val anchor = viewport.project(note.position, canvasSize)
        val fontSize = noteFontSize(note, viewport.pixelsPerMillimeter, density)
        val dx = tap.x - anchor.x
        val dy = tap.y - anchor.y
        val radians = Math.toRadians(note.rotationDegrees)
        val localX = dx * kotlin.math.cos(radians).toFloat() - dy * kotlin.math.sin(radians).toFloat()
        val localY = dx * kotlin.math.sin(radians).toFloat() + dy * kotlin.math.cos(radians).toFloat()
        val padding = 10f * density
        val estimatedTextWidth = (note.textWidthMm / note.heightMm * fontSize).toFloat()
        val withinLabel = localX in -padding..(estimatedTextWidth + padding) &&
            localY in (-fontSize - padding)..(fontSize * NOTE_TEXT_DESCENT_FACTOR.toFloat() + padding)
        if (withinLabel) index to (tap - anchor).getDistance() else null
    }.minByOrNull { it.second }?.first
}

private fun findEndpointAt(
    segments: List<WallSegment>,
    tap: Offset,
    canvasSize: IntSize,
    viewport: PlanViewportState,
    tolerancePx: Float
): PointMm? {
    val endpoints = (listOf(PointMm(0.0, 0.0)) + PlanGeometry.wallInteractionPoints(segments)).distinct()
    return endpoints
        .map { endpoint -> endpoint to (tap - viewport.project(endpoint, canvasSize)).getDistance() }
        .filter { (_, distance) -> distance <= tolerancePx }
        .minByOrNull { (_, distance) -> distance }
        ?.first
}

private fun findNearbyEndpoint(
    segments: List<WallSegment>,
    startPoint: PointMm,
    endPoint: PointMm,
    canvasSize: IntSize,
    viewport: PlanViewportState,
    tolerancePx: Float
): PointMm? {
    val endpoints = (listOf(PointMm(0.0, 0.0)) + PlanGeometry.wallInteractionPoints(segments))
        .distinct()
        .filterNot { it == startPoint }
    val endOnScreen = viewport.project(endPoint, canvasSize)
    return endpoints
        .map { point -> point to (viewport.project(point, canvasSize) - endOnScreen).getDistance() }
        .filter { (_, distancePx) -> distancePx <= tolerancePx }
        .minByOrNull { (_, distancePx) -> distancePx }
        ?.first
}

private const val NOTE_TEXT_DESCENT_FACTOR = 0.25
private const val ENDPOINT_SNAP_TOLERANCE_DP = 40f
private const val MILLIMETERS_PER_METER = 1_000.0
private const val DEFAULT_VIEW_HALF_SPAN_MM = 5_000.0

internal fun selectGridStepMm(minimumSpacingPx: Float, pixelsPerMillimeter: Float): Double {
    if (minimumSpacingPx <= 0f || pixelsPerMillimeter <= 0f) return MILLIMETERS_PER_METER
    val rawStep = minimumSpacingPx / pixelsPerMillimeter
    val magnitude = 10.0.pow(floor(log10(rawStep.toDouble())))
    return when {
        rawStep <= magnitude -> magnitude
        rawStep <= magnitude * 2.0 -> magnitude * 2.0
        rawStep <= magnitude * 5.0 -> magnitude * 5.0
        else -> magnitude * 10.0
    }
}

internal fun formatScaleCoordinate(coordinateMm: Double, divisionMm: Double): String {
    return when {
        divisionMm >= MILLIMETERS_PER_METER -> {
            val meters = coordinateMm / MILLIMETERS_PER_METER
            val decimalPlaces = ceil(log10(MILLIMETERS_PER_METER / divisionMm)).toInt().coerceIn(0, 3)
            String.format(java.util.Locale.ROOT, "%.${decimalPlaces}fm", meters)
        }
        divisionMm >= 10.0 -> "${(coordinateMm / 10.0).roundToLong()} cm"
        else -> "${coordinateMm.roundToLong()} mm"
    }
}

private data class SegmentHit(val index: Int, val distancePx: Float, val lineLengthPx: Float)

internal fun shouldSelectShortLineOnTap(
    lineLengthPx: Float,
    nearestEndpointDistancePx: Float,
    endpointTargetPx: Float
): Boolean = lineLengthPx <= endpointTargetPx * 2f &&
    nearestEndpointDistancePx > lineLengthPx / 4f

private fun findSegmentAt(
    segments: List<WallSegment>,
    tap: Offset,
    canvasSize: IntSize,
    viewport: PlanViewportState,
    tolerancePx: Float
): SegmentHit? {
    val wallShapes = PlanGeometry.wallFootprints(segments)
    return segments.indices.mapNotNull { index ->
        val segment = segments[index]
        val screenStart = viewport.project(segment.start, canvasSize)
        val screenEnd = viewport.project(segment.end, canvasSize)
        val lineDistance = distanceFromPointToSegment(tap, screenStart, screenEnd)
        val lineLengthPx = (screenEnd - screenStart).getDistance()
        val screenPolygons = wallShapes[index].map { polygon -> polygon.map { viewport.project(it, canvasSize) } }
        val isInsideWall = screenPolygons.any { polygon -> pointInPolygon(tap, polygon) }
        val wallEdgeDistance = screenPolygons
            .flatMap { polygon -> polygon.indices.map { edgeIndex ->
                distanceFromPointToSegment(tap, polygon[edgeIndex], polygon[(edgeIndex + 1) % polygon.size])
            } }
            .minOrNull()
        val distance = when {
            isInsideWall -> 0f
            wallEdgeDistance != null -> minOf(lineDistance, wallEdgeDistance)
            else -> lineDistance
        }
        if (distance <= tolerancePx) SegmentHit(index, distance, lineLengthPx) else null
    }
    .minByOrNull { it.distancePx }
}

private fun pointInPolygon(point: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    var previous = polygon.lastIndex
    for (current in polygon.indices) {
        val currentPoint = polygon[current]
        val previousPoint = polygon[previous]
        val crosses = (currentPoint.y > point.y) != (previousPoint.y > point.y) &&
            point.x < (previousPoint.x - currentPoint.x) *
            (point.y - currentPoint.y) / (previousPoint.y - currentPoint.y) + currentPoint.x
        if (crosses) inside = !inside
        previous = current
    }
    return inside
}

private fun distanceFromPointToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return (point - start).getDistance()

    val projection = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
        .coerceIn(0f, 1f)
    val closestPoint = Offset(start.x + projection * dx, start.y + projection * dy)
    return (point - closestPoint).getDistance()
}
