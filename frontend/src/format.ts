/** Drops trailing zeros from a decimal string for display: "50.000" -> "50", "12.500" -> "12.5", "500" stays. */
export const trimDecimals = (value: string) => (value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value)
