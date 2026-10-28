package com.example.meatballmerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFloodFillTest {
    private val bounds = CaptureFloodFill.Bounds(0f, 0f, 100f, 100f)

    private fun point(x: Float, y: Float) = CaptureFloodFill.Point(x, y)

    @Test
    fun closedSquareCapturesInteriorButNotExterior() {
        val boundary = listOf(point(30f, 30f), point(70f, 30f), point(70f, 70f), point(30f, 70f))
        val candidates = listOf(point(50f, 50f), point(15f, 15f), point(85f, 85f))

        assertEquals(setOf(0), CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f))
    }

    @Test
    fun incompleteTrailCannotEncloseArea() {
        val boundary = listOf(point(20f, 20f), point(80f, 20f))
        val candidates = listOf(point(50f, 50f))

        assertTrue(CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f).isEmpty())
    }

    @Test
    fun diagonalDiamondClosesWithoutFloodFillLeak() {
        val boundary = listOf(point(50f, 18f), point(82f, 50f), point(50f, 82f), point(18f, 50f))
        val candidates = listOf(point(50f, 50f), point(8f, 8f))

        assertEquals(setOf(0), CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f))
    }

    @Test
    fun candidatesOutsideArenaAreNeverCaptured() {
        val boundary = listOf(point(30f, 30f), point(70f, 30f), point(70f, 70f), point(30f, 70f))
        val candidates = listOf(point(50f, 50f), point(105f, 50f), point(-1f, 50f))

        assertEquals(setOf(0), CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f))
    }

    @Test
    fun loopNearArenaEdgeStillCapturesInterior() {
        val boundary = listOf(point(4f, 20f), point(35f, 20f), point(35f, 55f), point(4f, 55f))
        val candidates = listOf(point(18f, 35f), point(45f, 35f), point(2f, 35f))

        assertEquals(setOf(0), CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f))
    }

    @Test
    fun concaveLoopCapturesOnlyEnclosedCandidate() {
        val boundary = listOf(
            point(20f, 20f), point(80f, 20f), point(80f, 40f),
            point(45f, 40f), point(45f, 80f), point(20f, 80f)
        )
        val candidates = listOf(point(30f, 60f), point(65f, 60f), point(50f, 30f))

        assertEquals(setOf(0, 2), CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f))
    }

    @Test
    fun invalidCoordinatesFailSafely() {
        val boundary = listOf(point(20f, 20f), point(Float.NaN, 50f), point(80f, 80f))
        val candidates = listOf(point(50f, 50f))

        assertTrue(CaptureFloodFill.enclosedCandidateIndices(boundary, candidates, bounds, 2f).isEmpty())
    }
}
