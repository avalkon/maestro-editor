import com.digero.maestro.noteedit.NoteEditorApp;
import javax.swing.*;
import java.io.File;
import java.awt.*;

/** Run with xvfb-run on a machine without a physical display. */
public final class GuiSmoke {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame editor=NoteEditorApp.openWindow(null,new File(args[0]));
            if(!(editor.getContentPane() instanceof JTabbedPane tabs) || tabs.getTabCount()!=2)
                throw new AssertionError("MIDI and ABC tabs missing");
            JPanel abc=(JPanel)tabs.getComponentAt(1);
            boolean hasRoll=contains(abc,com.digero.maestro.noteedit.PianoRollPanel.class);
            boolean hasAll=contains(abc,JCheckBox.class);
            if(!hasRoll || !hasAll)throw new AssertionError("ABC roll or all-parts checkbox missing");
            editor.dispose();
            System.out.println("PASS: GUI loads supplied 10-part ABC; note editor and all-parts UI visible");
        });
    }
    private static boolean contains(Container container,Class<?> cls){
        for(Component c:container.getComponents()){
            if(cls.isInstance(c))return true;
            if(c instanceof Container child && contains(child,cls))return true;
        }
        return false;
    }
}
