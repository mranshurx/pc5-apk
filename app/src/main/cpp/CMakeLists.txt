cmake_minimum_required(VERSION 3.22.1)
project(pc5 CXX)

# --- compile-time obfuscation: XOR every secret into a byte array -----------
function(obf var str)
  string(HEX "${str}" hex)
  string(LENGTH "${hex}" hlen)
  math(EXPR n "${hlen} / 2")
  set(body "")
  if(n GREATER 0)
    math(EXPR last "${n} - 1")
    foreach(i RANGE ${last})
      math(EXPR o "${i} * 2")
      string(SUBSTRING "${hex}" ${o} 2 b)
      math(EXPR v "0x${b}")
      math(EXPR k "(0x5A + ${i} * 7) & 0xFF")
      math(EXPR x "${v} ^ ${k}")
      string(APPEND body "0x")
      string(APPEND body "${x}")
      string(APPEND body ",")
    endforeach()
  endif()
  set(${var}_BYTES "${body}" PARENT_SCOPE)
  set(${var}_LEN "${n}" PARENT_SCOPE)
endfunction()

obf(OBF_URL  "${BACKEND_URL}")
obf(OBF_CERT "${EXPECTED_CERT}")
obf(OBF_KEY  "${RAW1_KEY}")

file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/obf.h
"#pragma once
static const unsigned char OBF_URL[]  = {${OBF_URL_BYTES}0};
static const int OBF_URL_LEN  = ${OBF_URL_LEN};
static const unsigned char OBF_CERT[] = {${OBF_CERT_BYTES}0};
static const int OBF_CERT_LEN = ${OBF_CERT_LEN};
static const unsigned char OBF_KEY[]  = {${OBF_KEY_BYTES}0};
static const int OBF_KEY_LEN  = ${OBF_KEY_LEN};
")

add_library(pc5 SHARED keys.cpp)
target_include_directories(pc5 PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
target_compile_options(pc5 PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(pc5 PRIVATE -Wl,--gc-sections -Wl,--strip-all -s)
