package com.aresstack.spaceharvester.deck.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Im Zweifel abgelehnt: deutsche und englische Meldungen der Hosts, dazu die wiederkehrende Anmeldemaske. */
class LoginCheckTest {

    @Test
    void hostMessagesInGermanAndEnglishAreRejectionsWithTheirLine() {
        for (String line : new String[] {"Kennwort ungültig", "Passwort abgelaufen", "Benutzer gesperrt",
                "Sie sind nicht berechtigt", "INVALID PASSWORD", "Password expired", "Access revoked",
                "USER NOT AUTHORIZED", "Logon denied"}) {
            assertThat(LoginCheck.rejected("SYSTEM\n   " + line + "   \n", false)).as(line).contains(line);
        }
    }

    @Test
    void aNormalScreenIsAcceptedAndTheMaskComingBackIsNot() {
        assertThat(LoginCheck.rejected("ISPF Primary Option Menu\nOption ===>", false)).isEmpty();
        assertThat(LoginCheck.rejected("Userid ==>\nPasswort ==>", true)).isPresent();
    }
}
