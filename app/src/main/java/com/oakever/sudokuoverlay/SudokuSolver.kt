package com.oakever.sudokuoverlay

/** Small, deterministic 9x9 Sudoku solver. It counts at most two solutions so ambiguous
 * puzzles can be identified instead of silently presented as uniquely solved. */
object SudokuSolver {
    enum class Status { UNIQUE, MULTIPLE, NO_SOLUTION, INVALID }

    data class Result(
        val status: Status,
        val solution: IntArray? = null,
        val message: String
    )

    fun solve(input: IntArray): Result {
        if (input.size != 81 || input.any { it !in 0..9 }) {
            return Result(Status.INVALID, message = "Enter exactly 81 cells; each cell must be 0–9.")
        }

        val board = input.copyOf()
        val rowMask = IntArray(9)
        val colMask = IntArray(9)
        val boxMask = IntArray(9)

        for (index in board.indices) {
            val value = board[index]
            if (value == 0) continue
            val row = index / 9
            val col = index % 9
            val box = (row / 3) * 3 + col / 3
            val bit = 1 shl value
            if ((rowMask[row] and bit) != 0 ||
                (colMask[col] and bit) != 0 ||
                (boxMask[box] and bit) != 0
            ) {
                return Result(
                    Status.INVALID,
                    message = "There is a duplicate number in a row, column, or 3×3 box. Check the detected clues."
                )
            }
            rowMask[row] = rowMask[row] or bit
            colMask[col] = colMask[col] or bit
            boxMask[box] = boxMask[box] or bit
        }

        var firstSolution: IntArray? = null
        var solutionCount = 0

        fun search() {
            if (solutionCount >= 2) return

            var bestIndex = -1
            var bestCandidates = 0
            var bestCount = 10

            // Minimum-remaining-values selection reduces backtracking substantially.
            for (index in board.indices) {
                if (board[index] != 0) continue
                val row = index / 9
                val col = index % 9
                val box = (row / 3) * 3 + col / 3
                val used = rowMask[row] or colMask[col] or boxMask[box]
                val candidates = 0x3FE and used.inv()
                val count = Integer.bitCount(candidates)
                if (count == 0) return
                if (count < bestCount) {
                    bestCount = count
                    bestIndex = index
                    bestCandidates = candidates
                    if (count == 1) break
                }
            }

            if (bestIndex == -1) {
                solutionCount++
                if (firstSolution == null) firstSolution = board.copyOf()
                return
            }

            val row = bestIndex / 9
            val col = bestIndex % 9
            val box = (row / 3) * 3 + col / 3
            var candidates = bestCandidates
            while (candidates != 0 && solutionCount < 2) {
                val bit = candidates and -candidates
                candidates = candidates xor bit
                val value = Integer.numberOfTrailingZeros(bit)

                board[bestIndex] = value
                rowMask[row] = rowMask[row] or bit
                colMask[col] = colMask[col] or bit
                boxMask[box] = boxMask[box] or bit

                search()

                board[bestIndex] = 0
                rowMask[row] = rowMask[row] xor bit
                colMask[col] = colMask[col] xor bit
                boxMask[box] = boxMask[box] xor bit
            }
        }

        search()
        return when (solutionCount) {
            0 -> Result(Status.NO_SOLUTION, message = "This puzzle has no valid solution. Recheck the recognized numbers.")
            1 -> Result(Status.UNIQUE, firstSolution, "Solved. One valid solution was found.")
            else -> Result(Status.MULTIPLE, firstSolution, "More than one solution exists. Check the clues before using the answer.")
        }
    }
}
