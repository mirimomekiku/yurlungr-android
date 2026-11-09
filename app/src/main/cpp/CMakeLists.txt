cmake_minimum_required(VERSION 3.22.1)
project(yurlungr_terminal LANGUAGES C)

add_library(termux SHARED termux.c)
set_target_properties(termux PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED YES)
target_compile_options(termux PRIVATE -Wall -Wextra -Werror -Os)
# Explicitly retain both alignments even if the NDK's defaults change.
target_link_options(termux PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
