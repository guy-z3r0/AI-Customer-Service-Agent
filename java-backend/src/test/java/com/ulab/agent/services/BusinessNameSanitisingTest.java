package com.ulab.agent.services;

import com.ulab.agent.api.dto.BusinessUpsertRequest;
import com.ulab.agent.api.dto.BusinessView;
import com.ulab.agent.api.dto.EditorDtos;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.EscalationContact;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.ClientRepository;
import com.ulab.agent.repo.EscalationContactRepository;
import com.ulab.agent.repo.KbEntryRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * No line breaks in the things that end up in an email header (SEC-023).
 *
 * The business name is formatted into the subject of the escalation email, and
 * a contact's name and address go into its headers. A carriage return in a
 * header value is how a second header gets added to somebody else's message.
 * Jakarta Mail folds and encodes subjects, so this was unlikely to have been
 * exploitable — taking the characters out on the way in costs a line and means
 * nobody downstream has to know that.
 */
class BusinessNameSanitisingTest {

    private static final String WITH_A_HEADER_IN_IT =
            "Example Shop\r\nBcc: somebody-else@example.com";

    private BusinessService service() {
        BusinessRepository businesses = mock(BusinessRepository.class);
        when(businesses.save(any(Business.class))).thenAnswer(call -> call.getArgument(0));
        when(businesses.existsBySlug(anyString())).thenReturn(false);
        // Adding a contact checks the business exists first.
        when(businesses.findById(any(UUID.class))).thenReturn(Optional.of(new Business()));

        EscalationContactRepository contacts = mock(EscalationContactRepository.class);
        when(contacts.save(any(EscalationContact.class))).thenAnswer(call -> call.getArgument(0));

        return new BusinessService(businesses, mock(KbEntryRepository.class),
                mock(ClientRepository.class), mock(AiSettingsRepository.class), contacts);
    }

    @Test
    void aBusinessNameCannotCarryAnExtraEmailHeader() {
        BusinessView saved = service().create(new BusinessUpsertRequest(
                WITH_A_HEADER_IN_IT, null, null, null, null, null));

        assertFalse(saved.name().contains("\r"), saved.name());
        assertFalse(saved.name().contains("\n"), saved.name());
        assertEquals("Example Shop Bcc: somebody-else@example.com", saved.name(),
                "the text is kept, so nothing disappears silently — only the breaks go");
    }

    @Test
    void aSlugIsStillMadeOutOfWhateverIsLeft() {
        BusinessView saved = service().create(new BusinessUpsertRequest(
                WITH_A_HEADER_IN_IT, null, null, null, null, null));

        assertFalse(saved.slug().contains("\r"));
        assertFalse(saved.slug().contains("\n"));
    }

    @Test
    void anEscalationContactCannotEither() {
        EditorDtos.EscalationView contact = service().addEscalationContact(UUID.randomUUID(),
                new EditorDtos.EscalationRequest("Manager\r\nX-Spoof: yes",
                        "manager@example.com\r\nBcc: elsewhere@example.com", 1));

        assertFalse(contact.name().contains("\n"));
        assertFalse(contact.email().contains("\n"));
    }
}
