package io.github.dedon.damageblockswap;

import java.util.function.Predicate;

/**
 * Whether {@code /dbs add <a> <b>} may record the rule. No Minecraft classes (generic over the block
 * type), so it is unit-tested; in game {@code unsafe} is {@link RandomSwapService#isUnsafe}.
 */
final class ManualRule {
	private ManualRule() {
	}

	enum Check {
		OK,
		/** Both arguments name the same block: the rule would do nothing. */
		SAME_BLOCK,
		/** The first block may never be swapped (air, block entity, two-block block, never_swap tag). */
		UNSAFE_A,
		/** The second one may never be swapped. */
		UNSAFE_B
	}

	static <T> Check check(T a, T b, Predicate<T> unsafe) {
		if (a.equals(b)) {
			return Check.SAME_BLOCK;
		}
		if (unsafe.test(a)) {
			return Check.UNSAFE_A;
		}
		if (unsafe.test(b)) {
			return Check.UNSAFE_B;
		}
		return Check.OK;
	}
}
