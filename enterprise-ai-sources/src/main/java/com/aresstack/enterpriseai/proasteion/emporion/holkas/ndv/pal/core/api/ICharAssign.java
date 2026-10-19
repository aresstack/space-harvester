package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

public interface ICharAssign {
   byte getDecimalChar();

   byte getInputAssignment();

   byte getInputDelimiter();

   byte getTermCommandChar();

   byte getThousandSeperator();

   void setDecimalChar(byte var1);

   void setInputAssignment(byte var1);

   void setInputDelimiter(byte var1);

   void setTermCommandChar(byte var1);

   void setThousandSeperator(byte var1);
}
