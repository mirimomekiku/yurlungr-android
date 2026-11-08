package dev.yurlungr.app.projects

/**
 * One row of the history graph.
 * - [lane]: column of this commit's dot.
 * - [through]: lanes that pass straight through the row (other lines of history).
 * - [incoming]: lanes that end at the dot from above (this commit's children).
 * - [outgoing]: lanes the dot continues into below (its parents; a merge fans out).
 * - [width]: lanes in use, for sizing.
 */
data class GraphRow(
    val lane: Int,
    val through: List<Int>,
    val incoming: List<Int>,
    val outgoing: List<Int>,
    val width: Int,
    /** Lines sliding from one lane (top) to another (bottom), keeping a branch's line leftmost. */
    val shifts: List<Pair<Int, Int>> = emptyList(),
)

object GitGraph {
    /** Lays out [commits] (newest first, topological order) into lanes, like `git log --graph`. */
    fun layout(commits: List<GitCommit>): List<GraphRow> {
        val lanes = ArrayList<String?>() // lane -> hash it's waiting for
        val rows = ArrayList<GraphRow>(commits.size)
        for (c in commits) {
            var lane = lanes.indexOf(c.hash)
            val incoming = lanes.indices.filter { lanes[it] == c.hash }
            if (lane < 0) {
                // A branch tip nothing points at yet: take the first free lane.
                lane = lanes.indexOf(null).let { if (it < 0) { lanes += null; lanes.size - 1 } else it }
            }
            val through = lanes.indices.filter { lanes[it] != null && lanes[it] != c.hash }.toMutableList()
            val shifts = ArrayList<Pair<Int, Int>>()
            // Children converging on this commit collapse into its lane.
            for (i in incoming) lanes[i] = null

            val outgoing = ArrayList<Int>()
            c.parents.forEachIndexed { k, p ->
                val existing = lanes.indexOf(p)
                when {
                    // First parent already awaited further right: pull that line into this lane,
                    // so mainline history stays on the left like `git log --graph`.
                    existing > lane && k == 0 -> {
                        lanes[existing] = null
                        lanes[lane] = p
                        through.remove(existing)
                        shifts += existing to lane
                        outgoing += lane
                    }
                    existing >= 0 -> outgoing += existing // joins a line already waiting for that parent
                    k == 0 -> { lanes[lane] = p; outgoing += lane }
                    else -> {
                        val free = lanes.indexOf(null).let { if (it < 0) { lanes += null; lanes.size - 1 } else it }
                        lanes[free] = p
                        outgoing += free
                    }
                }
            }
            while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.lastIndex)
            val width = maxOf(lane + 1, (through + incoming + outgoing + shifts.map { it.first }).maxOrNull()?.plus(1) ?: 0, lanes.size)
            rows += GraphRow(lane, through, incoming.filter { it != lane } + if (lane in incoming) listOf(lane) else emptyList(), outgoing.distinct(), width, shifts)
        }
        return rows
    }
}
