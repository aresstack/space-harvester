package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

public interface IPalTimeoutHandler {
   boolean continueOperation();

   void addResultListener(IPalTimeoutResultListener var1);
}
