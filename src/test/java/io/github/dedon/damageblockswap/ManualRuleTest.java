package io.github.dedon.damageblockswap;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What /dbs add accepts. */
class ManualRuleTest {
	private static final Predicate<String> UNSAFE = Set.of("bedrock", "oak_door", "chest")::contains;

	@Test
	void ordinaryBlocksAreAccepted() {
		assertEquals(ManualRule.Check.OK, ManualRule.check("stone", "sand", UNSAFE));
	}

	@Test
	void sameBlockIsRejected() {
		assertEquals(ManualRule.Check.SAME_BLOCK, ManualRule.check("stone", "stone", UNSAFE));
		assertEquals(ManualRule.Check.SAME_BLOCK, ManualRule.check("bedrock", "bedrock", UNSAFE));
	}

	@Test
	void unsafeBlocksAreRejectedOnEitherSide() {
		assertEquals(ManualRule.Check.UNSAFE_A, ManualRule.check("bedrock", "stone", UNSAFE));
		assertEquals(ManualRule.Check.UNSAFE_B, ManualRule.check("stone", "oak_door", UNSAFE));
		assertEquals(ManualRule.Check.UNSAFE_A, ManualRule.check("chest", "oak_door", UNSAFE));
	}
}
