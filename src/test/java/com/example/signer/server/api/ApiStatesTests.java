package com.example.signer.server.api;

import com.example.signer.server.api.ApiContracts.ParticipantStateView;
import com.example.signer.server.api.ApiStates.ParticipantState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiStatesTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownOrMissingValuesNeverThrow() {
        assertThat(ParticipantState.parse("SOMETHING_NEW")).isEqualTo(ParticipantState.UNKNOWN);
        assertThat(ParticipantState.parse(null)).isEqualTo(ParticipantState.UNKNOWN);
        assertThat(ParticipantState.parse("")).isEqualTo(ParticipantState.UNKNOWN);
        assertThat(ParticipantState.parse(" SIGNED ")).isEqualTo(ParticipantState.SIGNED);
    }

    @Test
    void wireFormatStaysPlainStringsAndTypedAccessorsAreNotSerialized() throws Exception {
        String mine = mapper.writeValueAsString(
                new ParticipantStateView(ParticipantState.YOUR_TURN, 1, false, true));
        assertThat(mine).contains("\"myStateLabel\":\"YOUR_TURN\"").doesNotContain("\"state\"");
    }

    @Test
    void readsBackIntoTheCanonicalConstructor() throws Exception {
        ParticipantStateView back = mapper.readValue(
                "{\"myStateLabel\":\"SIGNED\",\"participantOrder\":2,"
                        + "\"signed\":true,\"waitingForMe\":false}", ParticipantStateView.class);
        assertThat(back.state()).isEqualTo(ParticipantState.SIGNED);
        assertThat(back.signed()).isTrue();
        assertThat(back.participantOrder()).isEqualTo(2);
    }
}
