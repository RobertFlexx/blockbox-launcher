package blockbox.launcher

import java.awt.*
import javax.swing.*
import javax.swing.border.BevelBorder
import javax.swing.plaf.ColorUIResource
import javax.swing.plaf.FontUIResource
import javax.swing.plaf.metal.DefaultMetalTheme
import javax.swing.plaf.metal.MetalLookAndFeel

val Paper = Color(0xF8F7F2)
val Chrome = Color(0xE8E6DF)
val Ink = Color(0x252D32)
val Muted = Color(0x626B70)
val Navy = Color(0x294A60)
val Green = Color(0x2D5B42)
val Line = Color(0xB7BAB4)

fun installTheme() {
    // A consistent, lightweight classic look on every host, with OS decorations.
    MetalLookAndFeel.setCurrentTheme(object : DefaultMetalTheme() {
        override fun getName() = "Blockbox Classic"
        override fun getPrimary1() = ColorUIResource(Navy.darker())
        override fun getPrimary2() = ColorUIResource(0xB4C4CD)
        override fun getPrimary3() = ColorUIResource(0xDCE5E9)
        override fun getSecondary1() = ColorUIResource(Line)
        override fun getSecondary2() = ColorUIResource(0xD1D2CC)
        override fun getSecondary3() = ColorUIResource(Chrome)
        override fun getControlTextFont() = FontUIResource(Font.SANS_SERIF, Font.PLAIN, 13)
        override fun getSystemTextFont() = controlTextFont
        override fun getUserTextFont() = controlTextFont
        override fun getMenuTextFont() = controlTextFont
        override fun getWindowTitleFont() = FontUIResource(Font.SANS_SERIF, Font.BOLD, 13)
        override fun getSubTextFont() = FontUIResource(Font.SANS_SERIF, Font.PLAIN, 12)
    })
    UIManager.setLookAndFeel(MetalLookAndFeel())
    UIManager.put("Button.gradient", null)
    UIManager.put("Button.border", BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.RAISED), BorderFactory.createEmptyBorder(5, 12, 5, 12)))
    UIManager.put("Button.focus", Navy)
    UIManager.put("Panel.background", Chrome)
    UIManager.put("Label.foreground", Ink)
    UIManager.put("TextField.background", Color.WHITE)
    UIManager.put("TextArea.background", Color.WHITE)
    UIManager.put("List.background", Color.WHITE)
    UIManager.put("Table.background", Color.WHITE)
    UIManager.put("Table.gridColor", Color(0xDDDFDA))
    UIManager.put("TabbedPane.selected", Paper)
    UIManager.put("TabbedPane.contentAreaColor", Paper)
    UIManager.put("TabbedPane.tabInsets", Insets(9, 14, 9, 14))
    UIManager.put("ScrollBar.width", 15)
    UIManager.put("SplitPane.dividerSize", 7)
    UIManager.put("ToolTip.background", Color(0xFFFDE8))
}

class BlockIcon(private val side: Int = 40) : Icon {
    override fun getIconWidth() = side
    override fun getIconHeight() = side
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val graphics = g.create() as Graphics2D
        graphics.translate(x, y)
        val scale = side / 40.0
        graphics.scale(scale, scale)
        graphics.color = Green
        graphics.fillRect(0, 0, 40, 40)
        graphics.color = Color(0xBDD0A4)
        graphics.fillPolygon(intArrayOf(8, 20, 32, 20), intArrayOf(13, 7, 13, 19), 4)
        graphics.color = Color(0x80A66C)
        graphics.fillPolygon(intArrayOf(8, 20, 20, 8), intArrayOf(14, 20, 33, 27), 4)
        graphics.color = Color(0xDCE2B8)
        graphics.fillPolygon(intArrayOf(21, 32, 32, 21), intArrayOf(20, 14, 27, 33), 4)
        graphics.dispose()
    }
}

class WidthPanel(layout: LayoutManager = BorderLayout()) : JPanel(layout), Scrollable {
    override fun getPreferredScrollableViewportSize() = Dimension(500, 450)
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = 24
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = (visibleRect.height - 24).coerceAtLeast(24)
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
