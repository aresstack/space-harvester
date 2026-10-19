package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type;

import java.util.ArrayList;

public interface IPalType {
   int intFromBuffer();

   String stringFromBuffer();

   void serialize();

   void restore();

   int get();

   ArrayList getRecord();

   void setRecord(ArrayList var1);

   void setPalVers(int var1);

   void setNdvType(int var1);

   void setServerCodePage(String var1);
}
