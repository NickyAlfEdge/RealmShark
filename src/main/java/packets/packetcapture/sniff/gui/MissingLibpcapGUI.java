package packets.packetcapture.sniff.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * GUI shown when libpcap cannot be loaded or /dev/bpf* cannot be opened on macOS/Linux.
 */
public class MissingLibpcapGUI extends JFrame {

    private static final String WIRESHARK_URL = "https://www.wireshark.org/download.html";

    public MissingLibpcapGUI(boolean permissionProblem) throws HeadlessException {
        super();
        setTitle(permissionProblem ? "Packet capture permission denied" : "Missing libpcap");

        JLabel line1;
        JLabel line2;
        JLabel line3;
        if (permissionProblem) {
            line1 = new JLabel("RealmShark could not open the network device for capture.");
            line2 = new JLabel("On macOS this needs root or read access to /dev/bpf*.");
            line3 = new JLabel("Either run with 'sudo java -jar <file>.jar' or install Wireshark's ChmodBPF helper:");
        } else {
            line1 = new JLabel("RealmShark could not load libpcap.");
            line2 = new JLabel("On macOS libpcap ships with the OS; if it is missing install it via Homebrew or Wireshark.");
            line3 = new JLabel("Wireshark installer:");
        }
        JLabel link = new JLabel(WIRESHARK_URL);
        JButton close = new JButton("Close");

        link.setForeground(Color.BLUE.darker());
        link.setCursor(new Cursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    Desktop.getDesktop().browse(new URI(WIRESHARK_URL));
                } catch (IOException | URISyntaxException ex) {
                    ex.printStackTrace();
                }
            }
        });
        close.addActionListener(e -> dispose());

        setLayout(new BoxLayout(getContentPane(), BoxLayout.Y_AXIS));
        FlowLayout left = new FlowLayout(FlowLayout.LEFT);
        left.setVgap(0);
        Panel spacer = new Panel();
        spacer.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 3));
        Panel p1 = new Panel(left); p1.add(line1);
        Panel p2 = new Panel(left); p2.add(line2);
        Panel p3 = new Panel(left); p3.add(line3); p3.add(link);
        Panel p4 = new Panel(new FlowLayout(FlowLayout.CENTER)); p4.add(close);
        add(spacer); add(p1); add(p2); add(p3); add(p4);

        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setVisible(true);
    }
}
