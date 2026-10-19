package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

public interface IPalTypeTimeStamp {
   int FLAG_CHECK = 1;
   int FLAG_GET = 2;
   int FLAG_NOOPERATION = 4;

   int getFlags();

   String getTimeStamp();

   String getUserId();
}
