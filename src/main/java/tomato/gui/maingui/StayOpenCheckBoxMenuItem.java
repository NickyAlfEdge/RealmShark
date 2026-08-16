package tomato.gui.maingui;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;

/**
 * A {@link JCheckBoxMenuItem} that does NOT close its enclosing menu when
 * toggled — enable/disable several bag filters in one visit without the popup
 * shutting between each click.
 *
 * Why the naive approach fails: {@code BasicMenuItemUI.doClick(...)} calls
 * {@code msm.clearSelectedPath()} BEFORE invoking the item's action, so by
 * the time {@code doClick} runs on this subclass the manager has already been
 * cleared. Instead we listen to path changes and cache the last non-empty
 * selection, then re-install it after {@code super.doClick()} fires.
 */
public class StayOpenCheckBoxMenuItem extends JCheckBoxMenuItem {

    private static MenuElement[] savedPath = new MenuElement[0];

    static {
        MenuSelectionManager.defaultManager().addChangeListener(e -> {
            MenuElement[] p = MenuSelectionManager.defaultManager().getSelectedPath();
            if (p != null && p.length != 0) {
                savedPath = p;
            }
        });
    }

    public StayOpenCheckBoxMenuItem(String text) {
        super(text);
    }

    @Override
    public void doClick(int pressTime) {
        super.doClick(pressTime);
        MenuSelectionManager.defaultManager().setSelectedPath(savedPath);
    }
}
