package com.example.signer.server.api;

import com.example.signer.server.api.ApiContracts.ParticipantStateView;
import com.example.signer.server.api.ApiContracts.TaskParticipantView;
import com.example.signer.server.api.ApiStates.ParticipantState;
import com.example.signer.server.api.ApiStates.SlotState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiStatesTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unknownOrMissingValuesNeverThrow() {
        assertThat(ParticipantState.parse("SOMETHING_NEW")).isEqualTo(ParticipantState.UNKNOWN);
        assertThat(ParticipantState.parse(null)).isEqualTo(ParticipantState.UNKNOWN);
        assertThat(SlotState.parse(" CURRENT ")).isEqualTo(SlotState.CURRENT);
        assertThat(SlotState.parse("")).isEqualTo(SlotState.UNKNOWN);
        assertThat(ParticipantState.parse("INVITED")).isEqualTo(ParticipantState.INVITED);
    }

    @Test
    void wireFormatStaysPlainStringsAndTypedAccessorsAreNotSerialized() throws Exception {
        String participant = mapper.writeValueAsString(
                new TaskParticipantView(1, "Ann", true, SlotState.CURRENT));
        assertThat(participant).contains("\"state\":\"CURRENT\"").doesNotContain("slotState");

        String mine = mapper.writeValueAsString(
                new ParticipantStateView(ParticipantState.YOUR_TURN, 1, false, true, false));
        assertThat(mine).contains("\"myStateLabel\":\"YOUR_TURN\"").doesNotContain("\"state\"");
    }

    @Test
    void readsBackIntoTheCanonicalConstructor() throws Exception {
        TaskParticipantView back = mapper.readValue(
                "{\"order\":2,\"name\":\"Bo\",\"you\":false,\"state\":\"SIGNED\"}",
                TaskParticipantView.class);
        assertThat(back.slotState()).isEqualTo(SlotState.SIGNED);
    }
}
