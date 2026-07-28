package to.sava.peranta.ui.setup

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals

class WizardAnswersSaverTest {

    private val scope = SaverScope { true }

    private fun roundTrip(answers: WizardAnswers): WizardAnswers? =
        with(WIZARD_ANSWERS_SAVER) { scope.save(answers) }?.let { WIZARD_ANSWERS_SAVER.restore(it) }

    /** 回答済みの選択は再生成越しにそのまま戻る。 */
    @Test
    fun answeredChoicesSurviveRoundTrip() {
        val answers = WizardAnswers(source = WizardSourceChoice.BE_SOURCE, forward = true)
        assertEquals(answers, roundTrip(answers))
    }

    /** 未回答（null）と回答済みの false を取り違えない。 */
    @Test
    fun unansweredAndFalseAreDistinguished() {
        assertEquals(WizardAnswers(), roundTrip(WizardAnswers()))
        assertEquals(
            WizardAnswers(source = WizardSourceChoice.JOIN, forward = false),
            roundTrip(WizardAnswers(source = WizardSourceChoice.JOIN, forward = false)),
        )
    }
}
