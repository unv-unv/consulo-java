package consulo.java.manifest.editor.models;

import org.osmorc.manifest.lang.psi.Header;
import org.osmorc.manifest.lang.psi.ManifestFile;
import consulo.language.editor.WriteCommandAction;
import consulo.ui.ex.awt.ColumnInfo;
import consulo.ui.ex.awt.table.ListTableModel;

/**
 * @author VISTALL
 * @since 13:40/05.05.13
 */
public class FileTableModel extends ListTableModel<Header> {
  private final ManifestFile myManifestFile;

  public FileTableModel(ManifestFile manifestFile) {
    super(new ColumnInfo.StringColumn(""));
    myManifestFile = manifestFile;
  }

  @Override
  public boolean isCellEditable(int rowIndex, int columnIndex) {
    return true;
  }

  @Override
  public void setValueAt(final Object aValue, int rowIndex, int columnIndex) {
    final Header rowValue = getRowValue(rowIndex);
    if (rowValue == null) {
      return;
    }
    WriteCommandAction.runWriteCommandAction(myManifestFile.getProject(), new Runnable() {
      @Override
      public void run() {
        rowValue.setName((String)aValue);
      }
    });
  }

  @Override
  public int getRowCount() {
    return myManifestFile.getHeaders().length;
  }

  @Override
  public Header getRowValue(int row) {
    return myManifestFile.getHeaders()[row];
  }

  @Override
  public Object getValueAt(int rowIndex, int columnIndex) {
    final Header rowValue = getRowValue(rowIndex);
    if (rowValue == null) {
      return "null";
    }
    else {
      return rowValue.getName();
    }
  }
}
