package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.IPalType;

public interface IPalTypeResultEx extends IPalType {
   String getShortText();

   int getRow();

   int getColumn();

   int getLengthSymbol();

   String getName();

   String getLibrary();

   int getKind();

   int getType();

   int getDatabaseId();

   int getFileNumber();

   String getSystemText();
}
