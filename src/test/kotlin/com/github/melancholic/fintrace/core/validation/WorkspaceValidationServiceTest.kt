package com.github.melancholic.fintrace.core.validation

import com.github.melancholic.fintrace.core.api.v1.dto.CreateWorkspaceRequest
import com.github.melancholic.fintrace.core.api.v1.dto.EditWorkspaceRequest
import com.github.melancholic.fintrace.core.exception.ValidationError
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * Rules that hold whoever the caller is, so they are tested without Spring, HTTP or a database —
 * the same shape as `OperationValidationServiceTest`.
 */
class WorkspaceValidationServiceTest {

	private val validation = WorkspaceValidationServiceImpl()

	@Test
	fun `accepts a well-formed creation`() {
		validation.validate(CreateWorkspaceRequest("budget-2026", "EUR"))
	}

	@Test
	fun `rejects a blank name`() {
		assertFailsWith<ValidationError> { validation.validate(CreateWorkspaceRequest("   ", "EUR")) }
	}

	@Test
	fun `rejects a name longer than the column`() {
		val tooLong = "a".repeat(ValidationConstants.MAX_NAME_LENGTH + 1)

		// varchar(60) would truncate silently in some databases; Postgres errors. Either way the
		// rule belongs here rather than being discovered at the driver.
		assertFailsWith<ValidationError> { validation.validate(CreateWorkspaceRequest(tooLong, "EUR")) }
	}

	@Test
	fun `rejects a name with characters outside the pattern`() {
		listOf("-leading-dash", "semi;colon", "slash/name", "dot.name").forEach {
			assertFailsWith<ValidationError>("expected '$it' to be rejected") {
				validation.validate(CreateWorkspaceRequest(it, "EUR"))
			}
		}
	}

	@Test
	fun `rejects a currency of the wrong shape`() {
		listOf("eur", "EURO", "EU", "E1R", "").forEach {
			assertFailsWith<ValidationError>("expected '$it' to be rejected") {
				validation.validate(CreateWorkspaceRequest("budget", it))
			}
		}
	}

	@Test
	fun `rejects a currency that is well-shaped but does not exist`() {
		// The point of the service-side check: a regex cannot tell ZZZ from EUR.
		assertFailsWith<ValidationError> { validation.validate(CreateWorkspaceRequest("budget", "ZZZ")) }
	}

	@Test
	fun `accepts every currency the runtime knows`() {
		listOf("EUR", "USD", "GBP", "CZK", "JPY").forEach {
			validation.validate(CreateWorkspaceRequest("budget", it))
		}
	}

	@Test
	fun `accepts an edit that changes nothing but the version`() {
		validation.validate(EditWorkspaceRequest(version = 3, workspaceName = null, defaultCurrency = null))
	}

	@Test
	fun `checks only the fields an edit actually carries`() {
		// An absent field means "leave it alone", so it must not be validated as if it were blank.
		validation.validate(EditWorkspaceRequest(version = 0, workspaceName = "renamed", defaultCurrency = null))
		validation.validate(EditWorkspaceRequest(version = 0, workspaceName = null, defaultCurrency = "USD"))
	}

	@Test
	fun `rejects an edit carrying an invalid currency`() {
		assertFailsWith<ValidationError> {
			validation.validate(EditWorkspaceRequest(version = 0, workspaceName = null, defaultCurrency = "ZZZ"))
		}
	}

	@Test
	fun `rejects an edit carrying an invalid name`() {
		assertFailsWith<ValidationError> {
			validation.validate(EditWorkspaceRequest(version = 0, workspaceName = "bad/name", defaultCurrency = null))
		}
	}

	@Test
	fun `accepts names in any script, with spaces`() {
		// The MoneyOK dump the importer feeds in is Russian: every account and category name
		// would be refused by an ASCII-only pattern (2.18b).
		listOf(
			"Мои деньги", "Наличные EUR", "Еда вне дома", "Default workspace", "budget-2026",
			"Ann's", "M&S", "Food, drinks", "Счёт №1", "Apt #3", "me@home",
		).forEach { validation.validate(EditWorkspaceRequest(version = 0, workspaceName = it, defaultCurrency = null)) }
	}

	@Test
	fun `accepts letters that need combining marks`() {
		// \p{L} alone cannot spell Hebrew, Arabic, Thai or Hindi, and it splits Latin in two:
		// "Café" precomposed passes while the identical-looking decomposed form does not, which
		// is a refusal the user cannot see the cause of.
		listOf(
			"Café",                       // NFD: e + combining acute
			"Ann’s",                       // the apostrophe iOS and Word actually produce
			"שָׁלום",
			"บัญชี",
		).forEach { validation.validate(EditWorkspaceRequest(version = 0, workspaceName = it, defaultCurrency = null)) }
	}

	@Test
	fun `rejects a name that does not start with a letter or digit`() {
		// Otherwise a leading space is representable, and two names differ by something invisible.
		listOf(" budget", "-budget", "[budget]", "́budget").forEach {
			assertFailsWith<ValidationError>("expected '$it' to be refused") {
				validation.validate(EditWorkspaceRequest(version = 0, workspaceName = it, defaultCurrency = null))
			}
		}
	}

	@Test
	fun `rejects a negative version`() {
		assertFailsWith<ValidationError> {
			validation.validate(EditWorkspaceRequest(version = -1, workspaceName = "renamed", defaultCurrency = null))
		}
	}
}
