package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.transaction.impl.NdvTimeStamp;
import java.util.Set;

public interface IFileProperties {
   int getLineNumberIncrement();

   int getKind();

   int getType();

   String getName();

   String getLongName();

   boolean isStructured();

   boolean isLinkedDdm();

   String getUser();

   PalDate getDate();

   int getSize();

   int getDatbaseId();

   int getFnr();

   String getCodePage();

   String getInternalLabelFirst();

   Set getOptions();

   NdvTimeStamp getTimeStamp();

   String getBaseLibrary();
}
