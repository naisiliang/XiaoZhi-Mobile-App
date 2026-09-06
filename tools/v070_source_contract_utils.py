def strip_kotlin_comments(source):
    """Remove Kotlin comments without interpreting comment markers in literals."""
    output = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith("//", index):
            newline = source.find("\n", index + 2)
            if newline < 0:
                break
            output.append("\n")
            index = newline + 1
            continue
        if source.startswith("/*", index):
            depth = 1
            index += 2
            while index < length and depth:
                if source.startswith("/*", index):
                    depth += 1
                    index += 2
                elif source.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    if source[index] == "\n":
                        output.append("\n")
                    index += 1
            continue
        if source[index] == chr(96):
            end = index + 1
            while end < length:
                if source[end] == "\\":
                    end += 2
                elif source[end] == chr(96):
                    end += 1
                    break
                else:
                    end += 1
            output.append(source[index:end])
            index = end
            continue
        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            if end < 0:
                output.append(source[index:])
                break
            output.append(source[index:end + 3])
            index = end + 3
            continue
        if source[index] in ('"', "'"):
            delimiter = source[index]
            end = index + 1
            while end < length:
                if source[end] == "\\":
                    end += 2
                elif source[end] == delimiter:
                    end += 1
                    break
                else:
                    end += 1
            output.append(source[index:end])
            index = end
            continue
        output.append(source[index])
        index += 1
    return "".join(output)


def strip_kotlin_literals(source):
    source = strip_kotlin_comments(source)
    output = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith('"""', index):
            end = source.find('"""', index + 3)
            index = length if end < 0 else end + 3
            continue
        if source[index] in ('"', "'"):
            delimiter = source[index]
            output.append('""' if delimiter == '"' else "''")
            end = index + 1
            while end < length:
                if source[end] == "\\":
                    end += 2
                elif source[end] == delimiter:
                    end += 1
                    break
                else:
                    end += 1
            index = end
            continue
        output.append(source[index])
        index += 1
    return "".join(output)
