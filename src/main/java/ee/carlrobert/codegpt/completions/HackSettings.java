package ee.carlrobert.codegpt.completions;

public class HackSettings {
  // TODO: Verify that this is the same as wait 250ms before sending a completion request
  public static int CompletionDebounceDelayMs = 250;

  // Allow multi-line suggestions
  public static boolean AllowMultilineSuggestions = true;

  // Trim the indent of the first line.
  public static boolean TrimIndentFirstCompletionLine = true;
}