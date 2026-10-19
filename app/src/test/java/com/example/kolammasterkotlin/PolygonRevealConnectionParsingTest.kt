package com.kolammaster.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolygonRevealConnectionParsingTest {
    @Test
    fun resolvesConnectionsOnlyAgainstPolygonsInTheirStep() {
        val root = JSONObject(
            """
            {
              "polygons": [
                {
                  "step": 1,
                  "polygons": [
                    {
                      "id": 12,
                      "points": [{"x": 1, "y": 2}, {"x": 3, "y": 4}, {"x": 5, "y": 6}]
                    },
                    {
                      "id": "hole-a",
                      "hole": true,
                      "points": [{"x": 7, "y": 8}, {"x": 9, "y": 10}, {"x": 11, "y": 12}]
                    }
                  ]
                },
                {
                  "step": 2,
                  "polygons": [
                    {
                      "id": 12,
                      "points": [{"x": 21, "y": 22}, {"x": 23, "y": 24}, {"x": 25, "y": 26}]
                    }
                  ]
                }
              ],
              "connections": [
                {
                  "step": 1,
                  "connections": [
                    {
                      "id": "valid",
                      "from": {"polygonId": 12.0, "vertexIndex": 1},
                      "to": {"polygonId": "hole-a", "vertexIndex": 2}
                    },
                    {
                      "id": "missing-polygon",
                      "from": {"polygonId": "missing", "vertexIndex": 0},
                      "to": {"polygonId": 12, "vertexIndex": 0}
                    },
                    {
                      "id": "out-of-range",
                      "from": {"polygonId": 12, "vertexIndex": 3},
                      "to": {"polygonId": "hole-a", "vertexIndex": 0}
                    },
                    {
                      "id": "fractional-index",
                      "from": {"polygonId": 12, "vertexIndex": 1.5},
                      "to": {"polygonId": "hole-a", "vertexIndex": 0}
                    }
                  ]
                },
                {
                  "step": 2,
                  "connections": [
                    {
                      "id": "step-scoped",
                      "from": {"polygonId": 12, "vertexIndex": 0},
                      "to": {"polygonId": "hole-a", "vertexIndex": 0}
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val steps = readStepPolygonData(root)
        val firstStep = steps.getValue(1)
        val secondStep = steps.getValue(2)

        assertEquals(listOf("12", "hole-a"), firstStep.polygons.map { it.id })
        assertTrue(firstStep.polygons[1].hole)
        assertFalse(firstStep.polygons[0].hole)
        assertEquals(1, firstStep.connections.size)
        assertEquals("valid", firstStep.connections.single().id)
        assertEquals(
            LessonConnectionEndpoint("12", 1, LessonPoint(3f, 4f)),
            firstStep.connections.single().from
        )
        assertEquals(
            LessonConnectionEndpoint("hole-a", 2, LessonPoint(11f, 12f)),
            firstStep.connections.single().to
        )
        assertTrue(secondStep.connections.isEmpty())
    }

    @Test
    fun packagesWithoutConnectionsRemainSupported() {
        val root = JSONObject(
            """
            {
              "polygons": [
                {
                  "step": 1,
                  "polygons": [
                    {
                      "id": "legacy",
                      "points": [{"x": 1, "y": 2}, {"x": 3, "y": 4}, {"x": 5, "y": 6}]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val step = readStepPolygonData(root).getValue(1)

        assertEquals(1, step.polygons.size)
        assertTrue(step.connections.isEmpty())
    }

    @Test
    fun parsesRevealAtFirstCrossingFlag() {
        val root = JSONObject(
            """
            {
              "polygons": [
                {
                  "step": 1,
                  "polygons": [
                    {
                      "id": 1,
                      "points": [{"x": 0, "y": 0}, {"x": 10, "y": 0}, {"x": 10, "y": 10}]
                    }
                  ]
                }
              ],
              "connections": [
                {
                  "step": 1,
                  "connections": [
                    {
                      "id": "reveal",
                      "from": {"polygonId": 1, "vertexIndex": 0},
                      "to": {"polygonId": 1, "vertexIndex": 1},
                      "revealAtFirstCrossing": true
                    },
                    {
                      "id": "noReveal",
                      "from": {"polygonId": 1, "vertexIndex": 1},
                      "to": {"polygonId": 1, "vertexIndex": 2}
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )
        val steps = readStepPolygonData(root)
        val step = steps.getValue(1)
        assertEquals(2, step.connections.size)
        val reveal = step.connections.find { it.id == "reveal" }!!
        val noReveal = step.connections.find { it.id == "noReveal" }!!
        assertTrue(reveal.revealAtFirstCrossing)
        assertFalse(noReveal.revealAtFirstCrossing)
    }
}
