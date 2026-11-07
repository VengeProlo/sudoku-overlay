package com.oakever.sudokuoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SudokuSolverTest {
    @Test
    fun solvesStandardPuzzleUniquely() {
        val puzzle = intArrayOf(
            5,3,0,0,7,0,0,0,0,
            6,0,0,1,9,5,0,0,0,
            0,9,8,0,0,0,0,6,0,
            8,0,0,0,6,0,0,0,3,
            4,0,0,8,0,3,0,0,1,
            7,0,0,0,2,0,0,0,6,
            0,6,0,0,0,0,2,8,0,
            0,0,0,4,1,9,0,0,5,
            0,0,0,0,8,0,0,7,9
        )

        val result = SudokuSolver.solve(puzzle)

        assertEquals(SudokuSolver.Status.UNIQUE, result.status)
        assertNotNull(result.solution)
        assertEquals(534678912, result.solution!!.sliceArray(0..8).joinToString("").toInt())
    }

    @Test
    fun reportsConflictingClues() {
        val puzzle = IntArray(81)
        puzzle[0] = 5
        puzzle[1] = 5
        val result = SudokuSolver.solve(puzzle)
        assertEquals(SudokuSolver.Status.INVALID, result.status)
    }

    @Test
    fun reportsEmptyPuzzleAsAmbiguous() {
        val result = SudokuSolver.solve(IntArray(81))
        assertEquals(SudokuSolver.Status.MULTIPLE, result.status)
    }
}
