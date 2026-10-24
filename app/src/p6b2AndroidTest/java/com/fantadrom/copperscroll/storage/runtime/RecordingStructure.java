package com.fantadrom.copperscroll.storage.runtime;
public final class RecordingStructure extends android.view.ViewStructure {
public String captured=""; public int children=-1;
public int addChildCount(int a0) {return 0;}
public void asyncCommit() {}
public android.view.ViewStructure asyncNewChild(int a0) {children++; return new RecordingStructure();}
public android.view.autofill.AutofillId getAutofillId() {return null;}
public int getChildCount() {return 0;}
public android.os.Bundle getExtras() {return null;}
public java.lang.CharSequence getHint() {return null;}
public java.lang.CharSequence getText() {return null;}
public int getTextSelectionEnd() {return 0;}
public int getTextSelectionStart() {return 0;}
public boolean hasExtras() {return false;}
public android.view.ViewStructure newChild(int a0) {children++; return new RecordingStructure();}
public android.view.ViewStructure.HtmlInfo.Builder newHtmlInfoBuilder(java.lang.String a0) {return null;}
public void setAccessibilityFocused(boolean a0) {}
public void setActivated(boolean a0) {}
public void setAlpha(float a0) {}
public void setAutofillHints(java.lang.String[] a0) {captured += String.valueOf(a0);}
public void setAutofillId(android.view.autofill.AutofillId a0) {}
public void setAutofillId(android.view.autofill.AutofillId a0, int a1) {}
public void setAutofillOptions(java.lang.CharSequence[] a0) {captured += String.valueOf(a0);}
public void setAutofillType(int a0) {}
public void setAutofillValue(android.view.autofill.AutofillValue a0) {captured += String.valueOf(a0);}
public void setCheckable(boolean a0) {}
public void setChecked(boolean a0) {}
public void setChildCount(int a0) {children=a0;}
public void setClassName(java.lang.String a0) {}
public void setClickable(boolean a0) {}
public void setContentDescription(java.lang.CharSequence a0) {captured += String.valueOf(a0);}
public void setContextClickable(boolean a0) {}
public void setDataIsSensitive(boolean a0) {}
public void setDimens(int a0, int a1, int a2, int a3, int a4, int a5) {}
public void setElevation(float a0) {}
public void setEnabled(boolean a0) {}
public void setFocusable(boolean a0) {}
public void setFocused(boolean a0) {}
public void setHint(java.lang.CharSequence a0) {captured += String.valueOf(a0);}
public void setHtmlInfo(android.view.ViewStructure.HtmlInfo a0) {}
public void setId(int a0, java.lang.String a1, java.lang.String a2, java.lang.String a3) {}
public void setInputType(int a0) {}
public void setLocaleList(android.os.LocaleList a0) {}
public void setLongClickable(boolean a0) {}
public void setOpaque(boolean a0) {}
public void setSelected(boolean a0) {}
public void setText(java.lang.CharSequence a0) {captured += String.valueOf(a0);}
public void setText(java.lang.CharSequence a0, int a1, int a2) {captured += String.valueOf(a0);}
public void setTextLines(int[] a0, int[] a1) {}
public void setTextStyle(float a0, int a1, int a2, int a3) {}
public void setTransformation(android.graphics.Matrix a0) {}
public void setVisibility(int a0) {}
public void setWebDomain(java.lang.String a0) {}
}
