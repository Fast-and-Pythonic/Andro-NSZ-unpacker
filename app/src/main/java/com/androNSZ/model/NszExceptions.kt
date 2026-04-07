package com.androNSZ.model

class NszConversionException(val code: Int, msg: String) : Exception(msg)
class CancelledException : Exception("Cancelled")
