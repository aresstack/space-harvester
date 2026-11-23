package com.aresstack.spaceharvester.deck.ndv.pal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeLibrary;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeObject;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IPalTypeSystemFile;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.ObjectType;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.PalTypeSystemFileFactory;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.ConnectKey;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.IDownloadResult;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api.IFileProperties;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.IPalTransactions;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.PalConnectResultException;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.api.PalResultException;
import com.aresstack.spaceharvester.deck.ndv.NdvConnection;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvProfile;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Der PAL-Adapter mit einer vorgespielten PAL-Schicht: Er ruft sie so auf wie MainframeMate (Verbindungsparameter,
 * Seiten bis zum Datenende, DDMs aus FDDM, Systemdatei des Objekts beim Laden).
 */
class PalNdvConnectorTest {

    private static final IPalTypeSystemFile FUSER = PalTypeSystemFileFactory.newInstance(10, 120,
            IPalTypeSystemFile.FUSER);
    private static final IPalTypeSystemFile FDDM = PalTypeSystemFileFactory.newInstance(10, 3,
            IPalTypeSystemFile.FDDM);

    /** Eine vorgespielte PAL-Schicht; was nicht gebraucht wird, wirft. */
    static final class ScriptedPal implements InvocationHandler {
        final List<String> calls = new ArrayList<>();
        Map<String, String> connectParameters;
        boolean connected;
        boolean refuseLogin;
        final Deque<IPalTypeLibrary[]> libraryPages = new ArrayDeque<>();
        final Deque<IPalTypeObject[]> objectPages = new ArrayDeque<>();
        final Deque<IPalTypeObject[]> ddmPages = new ArrayDeque<>();
        private Deque<IPalTypeObject[]> current;
        IPalTypeSystemFile downloadedFrom;
        String downloadedLibrary;
        String[] uploaded;

        IPalTransactions proxy() {
            return (IPalTransactions) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {IPalTransactions.class}, this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            calls.add(method.getName());
            switch (method.getName()) {
                case "connect" -> {
                    connectParameters = (Map<String, String>) args[0];
                    if (refuseLogin) {
                        throw new PalConnectResultException(838, "NAT0838 Passwort falsch", 3, 0);
                    }
                    connected = true;
                    return null;
                }
                case "isConnected" -> {
                    return connected;
                }
                case "disconnect" -> {
                    connected = false;
                    return null;
                }
                case "logon" -> {
                    return null;
                }
                case "getSystemFiles" -> {
                    return new IPalTypeSystemFile[] {PalTypeSystemFileFactory.newInstance(), FUSER, FDDM};
                }
                case "getLibrariesFirst", "getLibrariesNext" -> {
                    if (libraryPages.isEmpty()) {
                        throw new PalResultException(3, 3, "end of data");
                    }
                    return libraryPages.poll();
                }
                case "getObjectsFirst" -> {
                    IPalTypeSystemFile sysFile = (IPalTypeSystemFile) args[0];
                    current = sysFile.getKind() == IPalTypeSystemFile.FDDM ? ddmPages : objectPages;
                    return current.isEmpty() ? new IPalTypeObject[0] : current.poll();
                }
                case "getObjectsNext" -> {
                    if (current.isEmpty()) {
                        throw new PalResultException(3, 3, "end of data");
                    }
                    return current.poll();
                }
                case "createTransactionContext" -> {
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {(Class<?>) args[0]},
                            (p, m, a) -> null);
                }
                case "disposeTransactionContext" -> {
                    return null;
                }
                case "downloadSource" -> {
                    downloadedFrom = (IPalTypeSystemFile) args[1];
                    downloadedLibrary = (String) args[2];
                    IFileProperties props = (IFileProperties) args[3];
                    return download(new String[] {"* " + props.getName(), "END"});
                }
                case "uploadSource" -> {
                    uploaded = (String[]) args[4];
                    return null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }
        }
    }

    static IDownloadResult download(String[] lines) {
        return (IDownloadResult) Proxy.newProxyInstance(PalNdvConnectorTest.class.getClassLoader(),
                new Class<?>[] {IDownloadResult.class}, (p, m, a) -> m.getName().equals("getSource") ? lines : null);
    }

    static IPalTypeLibrary library(String name) {
        return (IPalTypeLibrary) Proxy.newProxyInstance(PalNdvConnectorTest.class.getClassLoader(),
                new Class<?>[] {IPalTypeLibrary.class}, (p, m, a) -> m.getName().equals("getLibrary") ? name + "  "
                        : m.getReturnType() == int.class ? 0 : null);
    }

    static IPalTypeObject object(String name, String longName, int type, int dbid, int fnr) {
        return (IPalTypeObject) Proxy.newProxyInstance(PalNdvConnectorTest.class.getClassLoader(),
                new Class<?>[] {IPalTypeObject.class}, (p, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getLongName" -> longName;
                    case "getType" -> type;
                    case "getKind" -> 1;
                    case "getDatabaseId" -> dbid;
                    case "getFileNumber" -> fnr;
                    case "getSourceSize" -> 42;
                    case "getUser" -> "PILOT";
                    default -> m.getReturnType() == int.class ? 0 : m.getReturnType() == boolean.class ? false : null;
                });
    }

    private final ScriptedPal pal = new ScriptedPal();
    private final PalNdvConnector connector = PalNdvConnector.over(pal::proxy);

    private NdvConnection connect() throws IOException {
        return connector.connect(new NdvProfile("10.0.0.7", 2700), "pilot", "geheim".toCharArray());
    }

    /** Angelo 23.11.: Proxy je Verbindung wählbar, Vorgabe direkt; ohne gültigen Weg wird nicht verbunden. */
    @Test
    void theProxyRouteIsAskedOnlyWhenTheConnectionChoseIt() throws IOException {
        java.util.List<NdvProfile> asked = new java.util.ArrayList<>();
        PalNdvConnector routed = PalNdvConnector.over(pal::proxy, profile -> {
            asked.add(profile);
            throw new IOException("Kein Weg über den Proxy zu " + profile.label());
        });
        routed.connect(new NdvProfile("10.0.0.7", 8011), "pilot", "geheim".toCharArray()).close();
        assertThat(asked).isEmpty();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> routed.connect(
                        new NdvProfile("10.0.0.7", 8011, "", true), "pilot", "geheim".toCharArray()))
                .isInstanceOf(IOException.class).hasMessageContaining("Kein Weg über den Proxy");
        assertThat(asked).hasSize(1);
    }

    @Test
    void connectsWithTheParametersOfMainframeMate() throws IOException {
        NdvConnection connection = connect();

        assertThat(connection.open()).isTrue();
        assertThat(pal.connectParameters).containsEntry(ConnectKey.HOST, "10.0.0.7")
                .containsEntry(ConnectKey.PORT, "2700").containsEntry(ConnectKey.USERID, "PILOT")
                .containsEntry(ConnectKey.PASSWORD, "geheim").containsEntry(ConnectKey.PARM, "CFICU=ON,CP=IBM01141")
                .containsEntry(ConnectKey.CLIENT_CP, "ISO-8859-1");
        connection.close();
        assertThat(connection.open()).isFalse();
    }

    @Test
    void aRefusedLoginBecomesAReadableIoException() {
        pal.refuseLogin = true;

        assertThatThrownBy(this::connect).isInstanceOf(IOException.class).hasMessageContaining("NDV-Login fehlgeschlagen")
                .hasMessageContaining("NAT0838");
    }

    @Test
    void listsAllLibraryPagesUntilTheEndOfData() throws IOException {
        pal.libraryPages.add(new IPalTypeLibrary[] {library("LAGER"), library("KASSE")});
        pal.libraryPages.add(new IPalTypeLibrary[] {library("SYSTEM")});

        assertThat(connect().libraries("*")).containsExactly("LAGER", "KASSE", "SYSTEM");
    }

    @Test
    void listsObjectsAndThenTheDdmsFromFddm() throws IOException {
        pal.objectPages.add(new IPalTypeObject[] {object("LAGER001", "", ObjectType.PROGRAM, 10, 120)});
        pal.objectPages.add(new IPalTypeObject[] {object("LAGERN01", "", ObjectType.SUBPROGRAM, 10, 120)});
        pal.ddmPages.add(new IPalTypeObject[] {object("", "ARTIKEL-STAMM", ObjectType.DDM, 10, 3)});
        List<NdvObject> seen = new ArrayList<>();
        List<Integer> totals = new ArrayList<>();

        int count = connect().objects("LAGER", "*", (page, total) -> {
            seen.addAll(page);
            totals.add(total);
            return true;
        });

        assertThat(count).isEqualTo(3);
        assertThat(totals).containsExactly(1, 2, 3);
        assertThat(seen).extracting(NdvObject::path)
                .containsExactly("LAGER/LAGER001.NSP", "LAGER/LAGERN01.NSN", "LAGER/ARTIKEL-STAMM.NSD");
        assertThat(seen.get(2).location().ddm()).isTrue();
        assertThat(pal.calls).contains("logon");
    }

    @Test
    void stoppingAfterTheFirstPageSkipsTheRest() throws IOException {
        pal.objectPages.add(new IPalTypeObject[] {object("A", "", ObjectType.PROGRAM, 10, 120)});
        pal.objectPages.add(new IPalTypeObject[] {object("B", "", ObjectType.PROGRAM, 10, 120)});
        pal.ddmPages.add(new IPalTypeObject[] {object("", "D", ObjectType.DDM, 10, 3)});

        int count = connect().objects("LAGER", "*", (page, total) -> false);

        assertThat(count).isEqualTo(1);
        assertThat(pal.ddmPages).as("DDMs nicht mehr angefragt").hasSize(1);
    }

    @Test
    void readsFromTheSystemFileOfTheObjectAndDdmsFromFddm() throws IOException {
        pal.objectPages.add(new IPalTypeObject[] {object("LAGER001", "", ObjectType.PROGRAM, 10, 120)});
        pal.ddmPages.add(new IPalTypeObject[] {object("", "ARTIKEL-STAMM", ObjectType.DDM, 10, 3)});
        NdvConnection connection = connect();
        List<NdvObject> seen = new ArrayList<>();
        connection.objects("LAGER", "*", (page, total) -> seen.addAll(page));

        assertThat(connection.read(seen.get(0))).isEqualTo("* LAGER001\nEND");
        assertThat(pal.downloadedFrom.getDatabaseId()).isEqualTo(10);
        assertThat(pal.downloadedFrom.getFileNumber()).isEqualTo(120);
        assertThat(pal.downloadedFrom.getKind()).isEqualTo(IPalTypeSystemFile.FUSER);
        assertThat(pal.downloadedLibrary).isEqualTo("LAGER");

        assertThat(connection.read(seen.get(1))).isEqualTo("* ARTIKEL-STAMM\nEND");
        assertThat(pal.downloadedFrom.getKind()).isEqualTo(IPalTypeSystemFile.FDDM);
        assertThat(pal.downloadedLibrary).as("DDMs liegen global").isEmpty();
    }

    @Test
    void writesTheLinesBack() throws IOException {
        pal.objectPages.add(new IPalTypeObject[] {object("LAGER001", "", ObjectType.PROGRAM, 10, 120)});
        NdvConnection connection = connect();
        List<NdvObject> seen = new ArrayList<>();
        connection.objects("LAGER", "*", (page, total) -> seen.addAll(page));

        connection.write(seen.getFirst(), "* neu\nEND");
        assertThat(pal.uploaded).containsExactly("* neu", "END");
    }

    @Test
    void picksFuserThenTheFirstValidSystemFile() {
        IPalTypeSystemFile empty = PalTypeSystemFileFactory.newInstance();
        IPalTypeSystemFile fnat = PalTypeSystemFileFactory.newInstance(10, 1, IPalTypeSystemFile.FNAT);
        assertThat(PalNdvConnection.pickBest(new IPalTypeSystemFile[] {empty, fnat, FUSER})).isSameAs(FUSER);
        assertThat(PalNdvConnection.pickBest(new IPalTypeSystemFile[] {empty, fnat})).isSameAs(fnat);
        assertThat(PalNdvConnection.pickBest(new IPalTypeSystemFile[] {empty})).isSameAs(empty);
        assertThat(PalNdvConnection.pickBest(new IPalTypeSystemFile[0])).isNull();
    }
}
