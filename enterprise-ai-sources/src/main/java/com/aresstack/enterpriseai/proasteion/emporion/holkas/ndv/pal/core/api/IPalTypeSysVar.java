package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.IPalType;

public interface IPalTypeSysVar extends IPalType {
   byte SV_LANGUAGE = 0;
   byte SV_STEPLIBS = 1;

   int getLanguage();

   int getKind();

   void setLanguage(int var1);
}
