package io.github.youndie.kachok.engine.choke

/**
 * Something bytes are spent from: a [TokenBucket] of one session, or a limit several share.
 *
 * The session asks the same three questions of either — is there a limit, how much is there now,
 * may I spend this — so the code that spends does not care whether the tokens are its own or the
 * whole client's ([B-151](../../../../../../../../docs/backlog/B-151-one-limit-for-the-whole-client.md)).
 */
public interface RateBudget {
    public val isUnlimited: Boolean

    /** What may be spent right now. [Long.MAX_VALUE] when there is no limit. */
    public val available: Long

    /** Spends [bytes] if they are there, all or nothing, and says whether it did. */
    public fun take(bytes: Long): Boolean

    /**
     * Hands back [bytes] taken a moment ago and not spent.
     *
     * For the one caller that takes from two budgets: when the second refuses, what the first gave
     * is returned, or a session that keeps being refused by the client's limit would drain its own
     * for nothing.
     */
    public fun giveBack(bytes: Long)
}

/**
 * A session's own budget and the client's, spent together.
 *
 * A byte goes out only when both have it. The session's comes first because it is the one nobody
 * else touches, so taking from it and giving back is safe; the shared one is taken second and only
 * when the first agreed, so a refusal there costs the other sessions nothing.
 */
public class BothBudgets(
    private val own: RateBudget,
    private val shared: RateBudget?,
) : RateBudget {
    override val isUnlimited: Boolean get() = own.isUnlimited && (shared?.isUnlimited ?: true)

    override val available: Long get() = minOf(own.available, shared?.available ?: Long.MAX_VALUE)

    override fun take(bytes: Long): Boolean {
        if (!own.take(bytes)) return false
        if (shared != null && !shared.take(bytes)) {
            own.giveBack(bytes)
            return false
        }
        return true
    }

    override fun giveBack(bytes: Long) {
        own.giveBack(bytes)
        shared?.giveBack(bytes)
    }
}
