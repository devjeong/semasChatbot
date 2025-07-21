package org.dev.semaschatbot

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import java.awt.FlowLayout

/**
 * `CustomButtonRenderer`는 IntelliJ IDEA 에디터 내에 사용자 정의 버튼을 렌더링하는 클래스입니다.
 * `EditorCustomElementRenderer` 인터페이스를 구현하여 인레이(Inlay) 요소로 버튼을 그립니다.
 * 이 렌더러는 코드 변경 제안에 대한 'Apply' 및 'Reject' 버튼을 시각적으로 표현하는 데 사용됩니다.
 * 실제 클릭 이벤트 처리는 이 렌더러 외부에서 관리되어야 합니다 (예: Gutter 아이콘 사용).
 * @param applyAction 'Apply' 버튼 클릭 시 실행될 람다 함수
 * @param rejectAction 'Reject' 버튼 클릭 시 실행될 람다 함수
 */
class CustomButtonRenderer(private val applyAction: () -> Unit, private val rejectAction: () -> Unit) : EditorCustomElementRenderer {
    /**
     * 인레이 요소의 너비를 픽셀 단위로 계산하여 반환합니다.
     * @param inlay 렌더링될 인레이 요소
     * @return 인레이 요소의 너비 (픽셀)
     */
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 200
    /**
     * 인레이 요소의 높이를 픽셀 단위로 계산하여 반환합니다.
     * 에디터의 라인 높이에 맞춰 설정됩니다.
     * @param inlay 렌더링될 인레이 요소
     * @return 인레이 요소의 높이 (픽셀)
     */
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight

    /**
     * 인레이 요소를 그리는 메서드입니다.
     * 이 메서드에서는 버튼의 배경색과 텍스트를 그립니다.
     * @param inlay 렌더링될 인레이 요소
     * @param g 그래픽 컨텍스트
     * @param targetRegion 인레이가 그려질 영역
     * @param textAttributes 텍스트 속성 (사용되지 않을 수 있음)
     */
    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        // 버튼의 배경을 회색으로 채웁니다.
        g.color = JBColor.LIGHT_GRAY
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)

        // 버튼 텍스트를 검은색으로 그립니다.
        g.color = JBColor.BLACK
        g.drawString("Apply | Reject", targetRegion.x + 5, targetRegion.y + targetRegion.height / 2)
    }
}