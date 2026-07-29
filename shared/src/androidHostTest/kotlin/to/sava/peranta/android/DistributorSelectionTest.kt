package to.sava.peranta.android

import kotlin.test.Test
import kotlin.test.assertEquals

/** UnifiedPush ディストリビュータの採否が、ntfy 以外を自動で採らないことを検証する。 */
class DistributorSelectionTest {

    private val ntfy = "io.heckel.ntfy"
    private val other = "com.example.distributor"
    private val another = "org.example.push"

    /** 候補が 1 つも無ければ採用対象が無い。 */
    @Test
    fun noCandidateWhenNoDistributorInstalled() {
        assertEquals(DistributorSelection.NoCandidate, distributorSelection(emptyList(), saved = null))
        assertEquals(DistributorSelection.NoCandidate, distributorSelection(emptyList(), saved = ntfy))
    }

    /** ntfy だけが居る通常構成では、確認を挟まず ntfy を採る。 */
    @Test
    fun adoptsNtfyWhenItIsTheOnlyCandidate() {
        assertEquals(DistributorSelection.Adopt(ntfy), distributorSelection(listOf(ntfy), saved = null))
    }

    /** ntfy を含む複数候補でも ntfy を採る（並び順に依らない）。 */
    @Test
    fun adoptsNtfyAmongMultipleCandidates() {
        assertEquals(DistributorSelection.Adopt(ntfy), distributorSelection(listOf(other, ntfy), saved = null))
    }

    /** ntfy が候補に無いときは、候補が単数でも複数でも自動採用しない。 */
    @Test
    fun doesNotAdoptWhenNtfyIsAbsent() {
        assertEquals(DistributorSelection.NoNtfy, distributorSelection(listOf(other), saved = null))
        assertEquals(DistributorSelection.NoNtfy, distributorSelection(listOf(other, another), saved = null))
    }

    /** 保存済みが現存するなら、それが ntfy でなくても選び直さない。 */
    @Test
    fun keepsSavedDistributorWhileItRemainsInstalled() {
        assertEquals(DistributorSelection.KeepSaved, distributorSelection(listOf(ntfy, other), saved = other))
        assertEquals(DistributorSelection.KeepSaved, distributorSelection(listOf(ntfy), saved = ntfy))
    }

    /** 保存済みがアンインストールされていれば、ntfy があるときだけ選び直す。 */
    @Test
    fun reselectsOnlyToNtfyWhenSavedDistributorIsGone() {
        assertEquals(DistributorSelection.Adopt(ntfy), distributorSelection(listOf(ntfy), saved = other))
        assertEquals(DistributorSelection.NoNtfy, distributorSelection(listOf(another), saved = other))
    }
}
