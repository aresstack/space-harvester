package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.impl.type.IPalType;

public interface IPalTypeMonitorInfo extends IPalType {
   String getSessionId();

   void setSessionId(String var1);

   String getEventFilter();

   void setEventFilter(String var1);
}
