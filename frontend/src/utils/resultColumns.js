/**
 * Helpers about the names a result set carries.
 *
 * When a data source reports one name for several columns, the platform numbers the extra columns itself (id, id_2,
 * id_3, ...), so a name of such a result no longer identifies a column of a table. Anything written by column name has
 * to refuse those results: the column an UPDATE would SET, the key which locates the row, and the column list of an
 * INSERT could otherwise point at a different column which happens to carry the same generated name.
 */

/**
 * @param {Array<boolean>|null|undefined} renamed per-column flags in the order of the columns of the result, as the
 *        backend sends them (absent for a result which was never read from a data source, or by an older backend)
 * @returns {boolean} whether the result carries at least one name the platform gave out
 */
export const hasRenamedColumns = (renamed) => Array.isArray(renamed) && renamed.some((flag) => !!flag);
