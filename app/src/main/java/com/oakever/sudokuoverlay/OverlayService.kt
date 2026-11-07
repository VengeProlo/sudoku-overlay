package com.oakever.sudokuoverlay

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.oakever.sudokuoverlay.SHOW_OVERLAY"
        const val EXTRA_PUZZLE = "puzzle"
        const val EXTRA_SOLUTION = "solution"
    }

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var solution = IntArray(81)
    private var puzzle = IntArray(81)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_SHOW) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps, then tap Show floating panel again.", Toast.LENGTH_LONG).show()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        puzzle = intent.getIntArrayExtra(EXTRA_PUZZLE) ?: IntArray(81)
        solution = intent.getIntArrayExtra(EXTRA_SOLUTION) ?: IntArray(81)
        if (solution.size != 81 || solution.any { it !in 1..9 }) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        removePanel()
        showPanel()
        return START_NOT_STICKY
    }

    private fun showPanel() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(22, 28, 40), dp(16), Color.rgb(75, 91, 116), dp(1))
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        val title = TextView(this).apply {
            text = "SUDOKU  •  SOLUTION"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(32), 1f))
        val copy = smallAction("COPY", 48)
        val close = smallAction("×", 34)
        header.addView(copy)
        header.addView(close)
        root.addView(header)

        val grid = GridLayout(this).apply {
            columnCount = 9
            rowCount = 9
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        val cellSize = dp(25)
        for (index in 0 until 81) {
            val isGiven = puzzle.getOrElse(index) { 0 } != 0
            val cell = TextView(this).apply {
                text = solution[index].toString()
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = if (isGiven) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isGiven) Color.rgb(235, 240, 248) else Color.rgb(116, 193, 255))
                background = rounded(
                    if (isGiven) Color.rgb(42, 53, 72) else Color.rgb(31, 40, 56),
                    dp(2),
                    Color.rgb(74, 88, 109),
                    dp(1)
                )
                setPadding(0, 0, 0, 0)
            }
            val params = GridLayout.LayoutParams().apply {
                width = cellSize
                height = cellSize
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            grid.addView(cell, params)
        }
        root.addView(grid)

        val footer = TextView(this).apply {
            text = "Blue = solved cells    White = original clues"
            setTextColor(Color.rgb(165, 177, 196))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }
        root.addView(footer)

        copy.setOnClickListener {
            val rows = (0 until 9).joinToString("\n") { row ->
                (0 until 9).joinToString("") { col -> solution[row * 9 + col].toString() }
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Sudoku solution", rows))
            Toast.makeText(this, "Solution copied", Toast.LENGTH_SHORT).show()
        }
        close.setOnClickListener {
            removePanel()
            stopSelf()
        }
        header.setOnTouchListener(DragTouchListener())

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(120)
        }

        panel = root
        layoutParams = params
        try {
            windowManager.addView(root, params)
        } catch (error: Exception) {
            panel = null
            layoutParams = null
            Toast.makeText(this, "Could not show floating panel: ${error.message ?: "overlay error"}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun smallAction(label: String, width: Int): TextView {
        val density = resources.displayMetrics.density
        val height = (30 * density).toInt()
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (label == "×") 18f else 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 61, 81), (6 * density).toInt(), Color.rgb(84, 102, 128), (1 * density).toInt())
            layoutParams = LinearLayout.LayoutParams((width * density).toInt(), height).apply {
                marginStart = (5 * density).toInt()
            }
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(strokeWidth, stroke)
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX - (event.rawX - downX)).toInt()
                    params.y = (startY + (event.rawY - downY)).toInt()
                    try { panel?.let { windowManager.updateViewLayout(it, params) } } catch (_: Exception) { }
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return false
        }
    }

    private fun removePanel() {
        val old = panel ?: return
        try { windowManager.removeView(old) } catch (_: Exception) { }
        panel = null
        layoutParams = null
    }

    override fun onDestroy() {
        removePanel()
        super.onDestroy()
    }
}
