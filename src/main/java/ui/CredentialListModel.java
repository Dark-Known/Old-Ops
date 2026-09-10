package ui;

import model.Credential;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A custom ListModel for managing a list of Credential objects.
 * Similar to TaskListModel but for credentials.
 */
public class CredentialListModel extends AbstractListModel<Credential> {

    private final List<Credential> credentials = new ArrayList<>();

    public CredentialListModel() {
    }

    @Override
    public int getSize() {
        return credentials.size();
    }

    @Override
    public Credential getElementAt(int index) {
        if (index < 0 || index >= credentials.size()) return null;
        return credentials.get(index);
    }

    public void setCredentials(List<Credential> newCredentials) {
        int oldSize = credentials.size();
        credentials.clear();
        credentials.addAll(newCredentials);
        
        if (oldSize > 0) {
            fireIntervalRemoved(this, 0, oldSize - 1);
        }
        if (credentials.size() > 0) {
            fireIntervalAdded(this, 0, credentials.size() - 1);
        }
    }

    public void findCredentialByUsername(String username) {
        for (int i = 0; i < credentials.size(); i++) {
            if (credentials.get(i).getUsername().equals(username)) {
                fireContentsChanged(this, i, i);
                return;
            }
        }
    }

    public List<Credential> getCredentials() {
        return new ArrayList<>(credentials);
    }
}
